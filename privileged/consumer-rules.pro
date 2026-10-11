# Shizuku loads this Binder service from the APK in a separate app_process and
# invokes its Context constructor reflectively. Manifest keep rules do not cover it.
-keep class io.github.sparkcyf.voicesipagent.privileged.shell.PrivilegedService {
    public <init>(android.content.Context);
}
# AGP merges dependency consumer rules. Ordinary API calls and AIDL dispatch are
# visible to R8; do not keep the entire Shizuku/privileged packages.
