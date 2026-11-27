/** Creates a private Android release key and local Gradle credentials; refuses to replace either. */
import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const directory = resolve(root, ".signing");
const keystore = resolve(directory, "yggdrasilmanager-release.p12");
const properties = resolve(root, "keystore.properties");
if (existsSync(keystore) || existsSync(properties)) {
  throw new Error("Signing files already exist. Back up and reuse the existing key; generation never overwrites it.");
}

mkdirSync(directory, { recursive: true, mode: 0o700 });
const password = randomBytes(32).toString("base64url");
const alias = "yggdrasilmanager";
const executable = process.env.JAVA_HOME
  ? resolve(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "keytool.exe" : "keytool")
  : "keytool";
const environment = { ...process.env, YGGDRASIL_NEW_KEY_PASSWORD: password };

function keytool(arguments_) {
  const result = spawnSync(executable, arguments_, { env: environment, stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`keytool failed with exit code ${result.status}`);
}

keytool([
  "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", keystore,
  "-storepass:env", "YGGDRASIL_NEW_KEY_PASSWORD", "-keypass:env", "YGGDRASIL_NEW_KEY_PASSWORD",
  "-alias", alias, "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
  "-validity", "10000", "-dname", "CN=YggdrasilManager Release, O=YggdrasilManager",
]);
chmodSync(keystore, 0o600);
// Persist immediately so an interrupted certificate export cannot orphan the private key.
writeFileSync(properties, [
  "storeFile=.signing/yggdrasilmanager-release.p12",
  `storePassword=${password}`, `keyAlias=${alias}`, `keyPassword=${password}`, "",
].join("\n"), { flag: "wx", mode: 0o600 });

keytool([
  "-exportcert", "-rfc", "-keystore", keystore,
  "-storepass:env", "YGGDRASIL_NEW_KEY_PASSWORD", "-alias", alias,
  "-file", resolve(directory, "release-certificate.pem"),
]);
console.log("Created .signing/yggdrasilmanager-release.p12 and keystore.properties. Back up both privately.");
