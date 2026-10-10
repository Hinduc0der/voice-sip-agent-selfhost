
/* --- android-voice-sip-agent: bulk frame copies for AudioMediaPort (inserted by build-pjsua2.sh) ---
 * The stock ByteVector is a List<Short> with one JNI call per byte; these move a whole frame at once.
 * Explicit typemaps: pjsua2's and_string.i turns every char* into a java String. */
#ifdef SWIGJAVA
/* Media callbacks run on pjmedia's clock thread 100x per second: attach it to the JVM once and
 * detach only when the thread ends, instead of attach/detach around every upcall. */
%begin %{
#define SWIG_JAVA_DETACH_ON_THREAD_END
#define SWIG_JAVA_ATTACH_CURRENT_THREAD_AS_DAEMON
%}
%typemap(jni)     (const char *vsaIn, int vsaInLen), (char *vsaOut, int vsaOutCap) "jbyteArray"
%typemap(jtype)   (const char *vsaIn, int vsaInLen), (char *vsaOut, int vsaOutCap) "byte[]"
%typemap(jstype)  (const char *vsaIn, int vsaInLen), (char *vsaOut, int vsaOutCap) "byte[]"
%typemap(javain)  (const char *vsaIn, int vsaInLen), (char *vsaOut, int vsaOutCap) "$javainput"
%typemap(in)      (const char *vsaIn, int vsaInLen), (char *vsaOut, int vsaOutCap) {
  if (!$input) { SWIG_JavaThrowException(jenv, SWIG_JavaNullPointerException, "null array"); return $null; }
  $1 = ($1_ltype) JCALL2(GetByteArrayElements, jenv, $input, 0);
  $2 = ($2_ltype) JCALL1(GetArrayLength, jenv, $input);
}
%typemap(argout)  (const char *vsaIn, int vsaInLen) {
  JCALL3(ReleaseByteArrayElements, jenv, $input, (jbyte *)$1, JNI_ABORT);
}
%typemap(argout)  (char *vsaOut, int vsaOutCap) {
  JCALL3(ReleaseByteArrayElements, jenv, $input, (jbyte *)$1, 0);
}
%extend std::vector<unsigned char> {
  /** Replaces the content with the whole array. */
  void assignBytes(const char *vsaIn, int vsaInLen) {
    $self->assign((const unsigned char *)vsaIn, (const unsigned char *)vsaIn + vsaInLen);
  }
  /** Copies up to out.length bytes into the array; returns the number copied. */
  int copyBytes(char *vsaOut, int vsaOutCap) {
    int n = (int)$self->size() < vsaOutCap ? (int)$self->size() : vsaOutCap;
    if (n > 0) memcpy(vsaOut, $self->data(), n);
    return n;
  }
}
#endif
