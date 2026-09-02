import { HwbpView } from "../features/hwbp/HwbpView";
import { SyscallView } from "../features/syscall/SyscallView";

export type Feature = "hwbp" | "syscall";

export function detectFeature(location: Pick<Location, "port" | "search"> = window.location): Feature {
  const requested = new URLSearchParams(location.search).get("feature");
  if (requested === "syscall" || requested === "hwbp") return requested;
  return location.port === "8766" ? "syscall" : "hwbp";
}

export function App({ feature = detectFeature() }: { feature?: Feature }) {
  return feature === "syscall" ? <SyscallView /> : <HwbpView />;
}
