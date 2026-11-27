# Superseded — v10.1.2 display/renderer patch

This note describes the previous v10.1.2 attempt. Its automatic MobileGlues override was not a reliable display fix and could produce a black/magenta screen on some Mali devices. Do not treat that renderer override as the current behavior.

Use v10.1.4, which removes the automatic renderer override and preserves the upstream per-instance/global renderer selection. This does not by itself guarantee that every mod or GPU driver will render correctly.
