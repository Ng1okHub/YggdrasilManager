import { cpSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = resolve(frontendRoot, "dist");
const target = resolve(frontendRoot, "../app/src/main/assets/observe-web");

if (!existsSync(source)) throw new Error("frontend/dist does not exist; run npm run build first");
rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });
cpSync(source, target, { recursive: true });
console.log(`Synced ${source} -> ${target}`);
