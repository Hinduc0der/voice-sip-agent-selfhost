# Third-party notices

Voice SIP Agent is licensed under the GNU General Public License v2 or later with an Apache-2.0 linking
exception.
Release APKs include the following third-party software.

## Native code (statically linked into `libpjsua2.so`)

| Component | Version | License | Source |
|---|---|---|---|
| pjproject (pjsip, pjmedia, pjlib, pjsua2 SWIG binding) | 2.17 | GPL-2.0-or-later | https://github.com/pjsip/pjproject |
| Third-party code bundled with pjproject (e.g. libsrtp) | as shipped with pjproject 2.17 | see each component's license in pjproject's `third_party/` directory (libsrtp: BSD-3-Clause) | https://github.com/pjsip/pjproject/tree/2.17/third_party |
| OpenSSL | 3.5.8 | Apache-2.0 | https://github.com/openssl/openssl |
| libopus | 1.6.1 | BSD-3-Clause (see upstream COPYING for patent notices) | https://opus-codec.org/ |
| LLVM libc++ (`libc++_shared.so`, from the Android NDK) | NDK r28c | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

pjproject is built by [`sip-pjsua2/native/build-pjsua2.sh`](sip-pjsua2/native/build-pjsua2.sh) from the
SHA-256-pinned upstream release tarballs, with the modifications in [`sip-pjsua2/native/`](sip-pjsua2/native/)
(`config_site.h`, `pjsua2-java-ext.i`, `pjsip-tls-wildcard.patch`). The [pjsip third-party license notice](https://docs.pjsip.org/en/latest/overview/license_3rd_party.html)
gives explicit permission to link pjproject with OpenSSL.

## Java / Kotlin libraries

| Component | License | Source |
|---|---|---|
| Shizuku API / provider (`dev.rikka.shizuku`) | Apache-2.0 | https://github.com/RikkaApps/Shizuku-API |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX (core, activity, Compose UI, Material 3) | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support |

The full text of the Apache License 2.0 is available at https://www.apache.org/licenses/LICENSE-2.0 and the
GNU GPL at https://www.gnu.org/licenses/.

## Corresponding source

For each release, the corresponding source is the tagged commit of this repository together with the upstream
tarballs listed above (exact versions and SHA-256 sums are in `build-pjsua2.sh`).
