# WristBridge Apple workspace

Read `Doc/00_LEEME_Y_COORDINACION.md`, `Doc/01_CONTRATO_COMPARTIDO.md`, `Doc/03_MAC_SWIFT.md` and `docs/apple/handoff-G0.md` before changes. For current Apple work also read `docs/apple/iphone-phases.md` and `docs/apple/handoff-P2.md`.

- Work on `development_codex`, the single Apple working branch requested by the user. Keep `main` as the integrated base; do not create extra task branches unless requested. The previous `feat/ios/xcode-validation` work has been integrated.
- Apple owns `apps/ios`, `packages/WristBridgeProtocol`, Apple scripts, tests and docs. Do not write Kotlin, Gradle or shared contracts assigned to Windows.
- Current scope: user authorized all iPhone work permitted by existing contracts/gates, including local P3 UI/privacy and P8 packaging (18 October 2026). P2 acceptance remains pending physical regression; wire/security require the joint contract. ADR-001 requires physical evidence. Do not start AI, macOS, health or sensitive BLE commands before their gates.
- Keep original `Doc/**` unchanged; its manifest hashes are checked. Corrections belong in `docs/apple/review.md` and handoffs.
- ANCS is consumed by the watch. iOS cannot treat its own notification authorization or a CoreBluetooth link as ANCS readiness.
- Wire format and characteristic properties are not frozen. No made-up codec or peer status parsing. Reserved UUIDs come from the original contract.
- `WristBridge` advertised name is an Apple proposal required for ASK filtering, pending Windows confirmation.
- Run `bash scripts/apple/check.sh`. Exit 2 means missing Xcode, not a passing iOS build. Use `bash scripts/apple/test-package.sh` for standalone package tests: it adds the bundled Testing framework/plugin paths and uses the native SwiftPM backend only under CLT. Full Xcode uses standard SwiftPM.
- Regenerate Xcode metadata with `python3 scripts/apple/generate_project.py` after adding source files. Keep shared scheme and UI target.
- No personal identifiers, certificates, tokens, real notification content or signing team in tracked files. Local signing overrides use ignored `apps/ios/Signing.local.xcconfig`.
- Update Apple handoff and evidence with actual PASS/FAIL/BLOCKED/NOT_RUN. Simulator, mocks and syntax parsing never approve G1.
