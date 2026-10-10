#include "ygg_backtrace.h"

#include <algorithm>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include <unwindstack/Maps.h>
#include <unwindstack/Elf.h>
#include <unwindstack/Memory.h>
#include <unwindstack/RegsArm64.h>
#include <unwindstack/Unwinder.h>
#include <unwindstack/UserArm64.h>

namespace {

class OverlayMemory final : public unwindstack::Memory {
public:
    OverlayMemory(std::shared_ptr<unwindstack::Memory> live_memory,
                  uint64_t snapshot_addr,
                  const uint8_t *snapshot,
                  size_t snapshot_size)
        : live_memory_(std::move(live_memory)),
          snapshot_addr_(snapshot_addr),
          snapshot_end_(snapshot_addr + snapshot_size),
          snapshot_(snapshot, snapshot + snapshot_size) {}

    size_t Read(uint64_t addr, void *dst, size_t size) override {
        uint8_t *out = static_cast<uint8_t *>(dst);
        size_t total = 0;

        while (total < size) {
            uint64_t current = addr + total;

            if (!snapshot_.empty() && current >= snapshot_addr_ && current < snapshot_end_) {
                size_t available = static_cast<size_t>(snapshot_end_ - current);
                size_t chunk = std::min(size - total, available);
                size_t offset = static_cast<size_t>(current - snapshot_addr_);
                memcpy(out + total, snapshot_.data() + offset, chunk);
                total += chunk;
                continue;
            }

            if (!live_memory_) break;

            size_t chunk = size - total;
            if (!snapshot_.empty() && current < snapshot_addr_) {
                uint64_t gap = snapshot_addr_ - current;
                chunk = std::min<size_t>(chunk, static_cast<size_t>(gap));
            }

            size_t copied = live_memory_->Read(current, out + total, chunk);
            total += copied;
            if (copied != chunk) break;
        }
        return total;
    }

    long ReadTag(uint64_t addr) override {
        return live_memory_ ? live_memory_->ReadTag(addr) : -1;
    }

    void Clear() override {
        if (live_memory_) live_memory_->Clear();
    }

private:
    std::shared_ptr<unwindstack::Memory> live_memory_;
    uint64_t snapshot_addr_;
    uint64_t snapshot_end_;
    std::vector<uint8_t> snapshot_;
};

static std::string to_hex(uint64_t value) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%" PRIx64, value);
    return buf;
}

static std::string basename_of(const std::string &path) {
    auto pos = path.find_last_of('/');
    if (pos == std::string::npos) return path;
    return path.substr(pos + 1);
}

static std::string normalized_map_name(const std::string &path) {
    static constexpr char deleted_suffix[] = " (deleted)";
    if (path.size() >= sizeof(deleted_suffix) - 1 &&
        path.compare(path.size() - (sizeof(deleted_suffix) - 1),
                     sizeof(deleted_suffix) - 1, deleted_suffix) == 0) {
        return path.substr(0, path.size() - (sizeof(deleted_suffix) - 1));
    }
    return path;
}

static bool library_name_matches(const std::string &requested,
                                 const std::string &map_path,
                                 const std::string &soname) {
    const std::string requested_name = basename_of(requested);
    const std::string normalized_path = normalized_map_name(map_path);
    return (!soname.empty() &&
            (soname == requested || soname == requested_name)) ||
           normalized_path == requested ||
           basename_of(normalized_path) == requested_name;
}

static bool mapped_elf_vaddr(uint64_t elf_offset, int64_t load_bias, uint64_t *out) {
    if (load_bias >= 0) {
        return !__builtin_add_overflow(
            elf_offset, static_cast<uint64_t>(load_bias), out);
    }
    const uint64_t magnitude = static_cast<uint64_t>(-(load_bias + 1)) + 1;
    if (elf_offset < magnitude) return false;
    *out = elf_offset - magnitude;
    return true;
}

static std::unique_ptr<unwindstack::Maps> create_maps(uint32_t pid, const char *maps_text,
                                                      std::string *out_error) {
    if (maps_text != nullptr && maps_text[0] != '\0') {
        auto maps = std::make_unique<unwindstack::BufferMaps>(maps_text);
        if (!maps->Parse()) {
            if (out_error) *out_error = "failed to parse supplied maps buffer";
            return nullptr;
        }
        if (maps->Total() == 0) {
            if (out_error) *out_error = "supplied maps buffer is empty after parse";
            return nullptr;
        }
        return maps;
    }

    auto maps = std::make_unique<unwindstack::RemoteMaps>(static_cast<pid_t>(pid));
    if (!maps->Parse()) {
        if (out_error) {
            *out_error =
                "failed to parse maps (app UID cannot open /proc/<pid>/maps; "
                "supply root-read maps text)";
        }
        return nullptr;
    }
    return maps;
}

}  // namespace

bool ygg_unwind_dwarf(uint32_t pid,
                      const ygg_hwbp_user_regs_snapshot &regs,
                      uint64_t stack_addr,
                      const uint8_t *stack,
                      uint32_t stack_size,
                      const char *maps_text,
                      std::vector<YggBacktraceFrame> *out_frames,
                      std::string *out_error) {
    if (!out_frames) return false;
    out_frames->clear();
    if (out_error) out_error->clear();
    if (!pid) {
        if (out_error) *out_error = "skip event without pid";
        return false;
    }

    std::unique_ptr<unwindstack::Maps> maps = create_maps(pid, maps_text, out_error);
    if (!maps) return false;

    unwindstack::arm64_user_regs arm64_regs = {};
    memcpy(arm64_regs.regs, regs.regs, sizeof(arm64_regs.regs));
    arm64_regs.sp = regs.sp;
    arm64_regs.pc = regs.pc;
    arm64_regs.pstate = regs.pstate;

    std::unique_ptr<unwindstack::Regs> uregs(unwindstack::RegsArm64::Read(&arm64_regs));
    // Live process memory may still be blocked for the app UID; OverlayMemory
    // prefers the HWBP stack snapshot which is enough for many DWARF walks.
    auto live_memory = unwindstack::Memory::CreateProcessMemory(static_cast<pid_t>(pid));
    auto process_memory = std::make_shared<OverlayMemory>(
        live_memory, stack_addr, stack, stack_size);
    unwindstack::Unwinder unwinder(256, maps.get(), uregs.get(), process_memory);
    unwinder.Unwind();

    if (unwinder.LastErrorCode() != unwindstack::ERROR_NONE && out_error) {
        *out_error = std::string("libunwindstack stopped: ") +
                     unwinder.LastErrorCodeString();
        if (unwinder.LastErrorCode() == unwindstack::ERROR_MEMORY_INVALID) {
            *out_error += " at 0x" + to_hex(unwinder.LastErrorAddress());
        }
    }

    if (unwinder.NumFrames() == 0) {
        if (out_error && out_error->empty()) {
            *out_error = std::string("libunwindstack produced no frames, error=") +
                         unwinder.LastErrorCodeString();
        }
        return false;
    }

    out_frames->reserve(unwinder.NumFrames());
    for (size_t i = 0; i < unwinder.NumFrames(); ++i) {
        const unwindstack::FrameData &frame = unwinder.frames()[i];
        YggBacktraceFrame item;
        item.pc = frame.pc;
        item.label = unwindstack::Unwinder::FormatFrame(unwindstack::ARCH_ARM64, frame, false);
        if (!frame.map_info) {
            item.module.clear();
            item.offset_hex.clear();
        } else {
            item.module = basename_of(static_cast<const std::string &>(frame.map_info->name()));
            item.offset_hex = to_hex(frame.rel_pc);
        }
        out_frames->push_back(std::move(item));
    }
    return true;
}

bool ygg_resolve_library_target(uint32_t pid,
                                const char *maps_text,
                                const char *library,
                                uint64_t offset,
                                uint64_t *out_address,
                                std::string *out_error) {
    if (!out_address) return false;
    if (out_error) out_error->clear();
    if (!pid) {
        if (out_error) *out_error = "pid must be positive";
        return false;
    }
    if (!library || library[0] == '\0') {
        if (out_error) *out_error = "library is required";
        return false;
    }

    std::unique_ptr<unwindstack::Maps> maps = create_maps(pid, maps_text, out_error);
    if (!maps) return false;

    auto process_memory = unwindstack::Memory::CreateProcessMemory(static_cast<pid_t>(pid));
    const std::string requested(library);
    for (const auto &map : *maps) {
        if (!map || map->name().empty()) continue;

        std::unique_ptr<unwindstack::Memory> elf_memory(map->CreateMemory(process_memory));
        if (!elf_memory) continue;
        unwindstack::Elf elf(elf_memory.release());
        if (!elf.Init() || !elf.valid()) continue;

        const std::string map_path = static_cast<const std::string &>(map->name());
        const std::string soname = elf.GetSoname();
        if (!library_name_matches(requested, map_path, soname)) continue;

        uint64_t map_vaddr = 0;
        if (!mapped_elf_vaddr(map->elf_offset(), elf.GetLoadBias(), &map_vaddr) ||
            map->start() < map_vaddr) {
            if (out_error) *out_error = "invalid ELF load bias for " + requested;
            return false;
        }
        const uint64_t runtime_base = map->start() - map_vaddr;
        if (__builtin_add_overflow(runtime_base, offset, out_address)) {
            if (out_error) *out_error = "library target address overflow";
            return false;
        }
        return true;
    }

    if (out_error) {
        *out_error = "failed to find loaded ELF with SONAME '" + requested + "'";
    }
    return false;
}

bool ygg_list_library_names(uint32_t pid,
                            const char *maps_text,
                            std::vector<std::string> *out_names,
                            std::string *out_error) {
    if (!out_names) return false;
    out_names->clear();
    if (out_error) out_error->clear();
    if (!pid) {
        if (out_error) *out_error = "pid must be positive";
        return false;
    }
    auto maps = create_maps(pid, maps_text, out_error);
    if (!maps) return false;

    auto process_memory = unwindstack::Memory::CreateProcessMemory(static_cast<pid_t>(pid));
    std::set<std::string> names;
    for (const auto &map : *maps) {
        if (!map || map->name().empty()) continue;
        std::unique_ptr<unwindstack::Memory> elf_memory(map->CreateMemory(process_memory));
        if (!elf_memory) continue;
        unwindstack::Elf elf(elf_memory.release());
        if (!elf.Init() || !elf.valid()) continue;

        // Android can map a library directly from an APK. Its maps path is then the APK,
        // so use the ELF SONAME just as library target resolution does.
        std::string name = elf.GetSoname();
        if (name.empty()) {
            name = basename_of(normalized_map_name(static_cast<const std::string &>(map->name())));
            const auto marker = name.rfind(".so");
            if (marker == std::string::npos ||
                (marker + 3 != name.size() && name[marker + 3] != '.')) continue;
        }
        names.insert(name);
    }
    out_names->assign(names.begin(), names.end());
    return true;
}
