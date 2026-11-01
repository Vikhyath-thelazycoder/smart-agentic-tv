# Security

## Risks you should know about

**ADB is the biggest risk.** The key helper needs ADB running on the TV. On the tested board, ADB has no password: anyone on your network who reaches port 5555 gets full control of the TV.

- The app closes that port with a firewall rule when you choose **Close the TV's debugging port**.
- **That needs ADB root.** If your board doesn't allow it, the status shows `problem` and **the port stays open**. Then keep the TV on a network you trust, or a guest network, and turn ADB off if you stop using the app.
- **Each restart leaves a short gap.** The firewall rule is put back after the TV starts. We measured about 92 seconds open on the tested board.

**Other things to know:**

- Anyone with a pairing code can control the TV and replace its API keys. Codes last 2 minutes and allow 3 tries.
- The app download page on port 47802 is plain HTTP on your Wi-Fi, with no password. It only serves the phone app, which has no keys.
- A build with keys in `config.properties` contains those keys. Don't share it.
- Factory menus, "Upgrade" and "Restore" screens can damage boards. This project keeps away from them, and you should too.

The software comes with no warranty. See the [LICENSE](LICENSE).

## Reporting a problem

Please don't open a public issue for a security problem. Use [private vulnerability reporting](../../security/advisories/new) on this repository.

Include what you found, how to reproduce it, and the board if that matters. Never include real API keys.
