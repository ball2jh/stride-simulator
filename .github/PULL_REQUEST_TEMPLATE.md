**What this changes and why**

**Checklist**

- [ ] `./gradlew build` is green locally.
- [ ] No digest, expected hash or fixture byte was changed to make a failing test pass. If a recorded value
      legitimately changed, the description explains the new behavior and how the new value was established.
- [ ] Javadoc and, if needed, the README or VALIDATION.md are updated.
- [ ] `CHANGELOG.md` has an entry under `Unreleased` for anything a user of the library would notice.
- [ ] Mechanics changes cite the vanilla class and method they follow and add a focused regression test.
