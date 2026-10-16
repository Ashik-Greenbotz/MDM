// Check of the kiosk APK signing guard: node test-apk-signer.js <prod-signed.apk> <other-key.apk>
// The production-signed kiosk APK must pass, the other one must be refused, and a non-kiosk file is ignored.
const fs = require("fs"), assert = require("assert");
const { apkSigner, checkKioskApk } = require("./server.js");
const [good, bad] = process.argv.slice(2).map(f => fs.readFileSync(f));
assert.strictEqual(apkSigner(good), "7478efb4dab73459e54da7d92ff794e1ea1e23ba215c1da686195b31d888b6ee");
checkKioskApk(good);
assert.throws(() => checkKioskApk(bad), /different key/);
checkKioskApk(Buffer.from("not an apk"));
console.log("PASS: production key accepted (" + apkSigner(good).slice(0, 8) + "), other key refused (" + apkSigner(bad).slice(0, 8) + ")");
process.exit(0);
