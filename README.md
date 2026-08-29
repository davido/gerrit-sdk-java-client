# gerrit-sdk-java-client

A standalone **Bazel** consumer of the generated Gerrit Java SDK
([`davido/gerrit-sdk-java`](https://github.com/davido/gerrit-sdk-java)), fetched **from
JitPack** via `rules_jvm_external` / `maven.install`. It implements a colored,
Web-UI-style `get-change-detail` — the Java twin of the Rust/Go/Python/TypeScript
examples — using only the generated SDK code.

This is the Bazel-consumption half of the Java story: exactly how a **Gerrit plugin**
(itself a Bazel project) would depend on the SDK.

```
  gerrit-sdk-java        JitPack                    this repo (Bazel)
  (Maven SDK)     -->    builds tag -> Maven   -->  rules_jvm_external / maven.install
                        artifact on demand         -> java_binary get-change-detail
```

## Run it

```bash
bazelisk run //:get-change-detail -- --change 622261
#   other change / server:
bazelisk run //:get-change-detail -- --url https://gerrit-review.googlesource.com --change 621763
#   plain (no color): add  --no-color
```

Bazel resolves `com.github.davido:gerrit-sdk-java` from JitPack (and its okhttp/gson
transitive deps from Maven Central), compiles `GetChangeDetail.java` against the
generated `com.google.gerrit.client.*` classes, and runs it against a live Gerrit. The
`)]}'` XSSI guard is stripped by the SDK's `GerritXssiInterceptor`
(`GerritXssiInterceptor.newClient(base)`).

## Wiring

`MODULE.bazel`:
```python
maven.install(
    artifacts = ["com.github.davido:gerrit-sdk-java:246c0d5"],
    repositories = ["https://jitpack.io", "https://repo1.maven.org/maven2"],
)
```

**Note on the pinned coordinate.** It pins a **commit** (`246c0d5`), not the tag: JitPack
cached a *pre-fix failure* for the `v3.15.0-SNAPSHOT` tag (before the SDK gained its Maven
wrapper), and JitPack does not rebuild a force-moved tag. Clear that cached build in the
JitPack UI (`https://jitpack.io/#davido/gerrit-sdk-java`) and the coordinate can become
`com.github.davido:gerrit-sdk-java:v3.15.0-SNAPSHOT`. Commit builds are unaffected and
green.

`.bazelrc` sets the Java language level to 21 (Gerrit's level; the example uses switch
expressions).

## License

Apache 2.0. See [LICENSE.txt](LICENSE.txt).
