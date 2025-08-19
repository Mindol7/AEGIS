# AEGIS (Android Evidence Guard for Integrity and Survival)

Developer : Minhyuk Cho, Seungmin Lee, Sungwon Jeong, Seong-je Cho, Minkyu Park  
Contact Mail : cgumgek8@gmail.com
Paper : FSI: Digital Investigation (Preprint, 2025) ; _**Under Review**_
Tool Name : AEGIS  

---

## Usage

### Command (Android)

```bash
Install AEGIS.apk on target Android device
Logs will be generated and stored in protected internal storage
Collected logs are transmitted to Remote Server (Spring Boot + MongoDB)
Forensic Analyzer reconstructs timeline and generates report
AEGIS automatically records and transmits:
Anti-Forensic attempts (timestamp manipulation, logcat -c, reboot/shutdown)

File system events (create, modify, delete)

Calling activity (incoming, outgoing, duration, rejection)

SMS messages (send/receive)

Bluetooth connections & streaming events

App execution & UI interactions

Generated logs are integrity-protected using SHA-256 hash values, and uploaded logs are verified on the server before being stored.

Precautions
This tool is designed for forensic research and investigation purposes.
It requires explicit installation on the target device (no stealth mode).
The captured data includes sensitive personal information (calls, messages, timestamps), so proper authorization and legal compliance must be ensured.

If you are using this tool in research or open-source projects, please cite our paper in FSI: Digital Investigation.

Example Report Output
Each forensic report includes:

Event type and occurrence time

Original log message contents

Tampering detection results

Server-device timestamp offset

SHA-256 hash value for integrity verification

Comparison to Existing Tools
AEGIS provides:

✅ Remote storage without ADB requirement

✅ Integrity verification (SHA-256)

✅ Anti-Forensic detection (logcat -c, timestamp manipulation, reboot)

✅ Automated timeline reconstruction

✅ Structured forensic reporting

Other tools (e.g., DroidWatch, DELTA, LogExtractor) lack one or more of these essential features.

Citation
If you use AEGIS in your research, please cite:

bibtex
@article{choa2025aegis,
  title={AEGIS: Android Evidence Guard tool for Integrity and Survival},
  author={Cho, Minhyuk and Lee, Seungmin and Jeong, Sungwon and Cho, Seong-je and Park, Minkyu},
  journal={FSI: Digital Investigation},
  year={2025}
}
