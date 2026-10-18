# TODO

Work on the designer that is known about and not done. Delete an entry when it is done rather than ticking it.

- [ ] **Update all version numbers** in `pom.xml` to the siblings' current versions, which have moved on since:
      `vexelray.version`, `vexelray-gui.version` and `vexelray-framework.version` are 0.1.0-SNAPSHOT (now
      0.1.1-SNAPSHOT), and `tactroller.version` is 1.0-SNAPSHOT (now 1.1-SNAPSHOT). Until then the build uses stale
      jars from `~/.m2`, and the Linux and macOS profiles fail outright: `tactroller-linux` and `tactroller-macos`
      1.0-SNAPSHOT are not installed there.
