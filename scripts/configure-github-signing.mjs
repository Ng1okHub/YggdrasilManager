/** Uploads the locally generated release credentials as Actions secrets to an explicit repository. */
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repository = process.argv[2];
if (process.argv.length !== 3 || !/^[\w.-]+\/[\w.-]+$/.test(repository ?? "")) {
  throw new Error("Usage: node scripts/configure-github-signing.mjs OWNER/REPOSITORY (requires gh auth login)");
}
const properties = Object.fromEntries(readFileSync(resolve(root, "keystore.properties"), "utf8")
  .split(/\r?\n/).filter(line => line.trim() && !line.trim().startsWith("#"))
  .map(line => { const index = line.indexOf("="); return [line.slice(0, index).trim(), line.slice(index + 1).trim()]; }));
for (const name of ["storeFile", "storePassword", "keyAlias", "keyPassword"]) {
  if (!properties[name]) throw new Error(`Missing ${name} in keystore.properties`);
}
const secrets = {
  YGGDRASIL_KEYSTORE_BASE64: readFileSync(resolve(root, properties.storeFile)).toString("base64"),
  YGGDRASIL_KEYSTORE_PASSWORD: properties.storePassword,
  YGGDRASIL_KEY_ALIAS: properties.keyAlias,
  YGGDRASIL_KEY_PASSWORD: properties.keyPassword,
};
for (const [name, value] of Object.entries(secrets)) {
  // stdin keeps private values out of process arguments and shell history.
  const result = spawnSync("gh", ["secret", "set", name, "--repo", repository], { cwd: root, input: value, encoding: "utf8" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Could not set ${name}: ${result.stderr}`);
  console.log(`Configured ${name} for ${repository}`);
}
