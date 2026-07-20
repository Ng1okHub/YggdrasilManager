#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "yggdrasil.h"

struct YggBacktraceFrame {
    uint64_t pc = 0;
    std::string label;
    std::string module;
    std::string offset_hex;
};

// maps_text: optional /proc/<pid>/maps contents (e.g. read via root). When
// non-empty, BufferMaps is used so parsing does not depend on the app UID
// being able to open /proc/<pid>/maps directly.
bool ygg_unwind_dwarf(uint32_t pid,
                      const ygg_hwbp_user_regs_snapshot &regs,
                      uint64_t stack_addr,
                      const uint8_t *stack,
                      uint32_t stack_size,
                      const char *maps_text,
                      std::vector<YggBacktraceFrame> *out_frames,
                      std::string *out_error);

bool ygg_resolve_library_target(uint32_t pid,
                                const char *maps_text,
                                const char *library,
                                uint64_t offset,
                                uint64_t *out_address,
                                std::string *out_error);
