# AEGIS: A robust log preservation and anti-forensics resilient framework for android digital investigation

Developer : Minhyuk Cho, Seungmin Lee, Sungwon Jeong, Seong-je Cho, Minkyu Park  
Contact Mail : cgumgek8@gmail.com  
Paper : [FSI: Digital Investigation](https://www.sciencedirect.com/science/article/pii/S2666281726001332?dgcid=coauthor)  

---  


## Acknowledgement

+ This work was supported by Basic Science Research Program through the National Research Foundation of Korea (NRF) funded by the Ministry of Science and ICT (No. 2021R1A2C2012574).  
![시그니처 가로형_영문조합형](https://user-images.githubusercontent.com/64211521/80204259-7e798980-8663-11ea-95f1-ff19ccb86a77.jpg)

## Usage

### Command (Android)

```bash
Install AEGIS.apk on target Android device
Logs will be generated and stored in protected internal storage
Collected logs are transmitted to Remote Server (Spring Boot + MongoDB)
Forensic Analyzer reconstructs timeline and generates report
```

---

## Deployment Process

AEGIS is not deployed through the Google Play Store or any official Google platform.

Instead, AEGIS is provided as a **sideloaded APK** and must be installed manually on the target Android device.

To install AEGIS:

1. Obtain the `AEGIS.apk` file from the authorized deployer or project maintainer.
2. Transfer the APK file to the target Android device.
3. Allow installation from unknown sources if required by the Android system.
4. Install `AEGIS.apk` manually.
5. After installation, grant the required permissions described in the installation process below.

---

## Installation Process

<img width="1565" height="1036" alt="AEGIS installation process" src="https://github.com/user-attachments/assets/4b4317b5-f275-4c84-8ee4-b71dfeef7b04" />

AEGIS requires several Android permissions to collect forensic events from the device.  
After installing `AEGIS.apk`, grant the permissions shown in the installation process image.

### Step 1. Enable Accessibility Service

AEGIS uses the Android Accessibility Service to monitor UI-level interactions and foreground application changes.

1. Open **Settings**.
2. Go to **Accessibility**.
3. Select **Installed apps**.
4. Select **AEGIS**.
5. Turn on the AEGIS accessibility service.
6. When the permission dialog appears, tap **Allow**.

This permission allows AEGIS to collect:

- UI click events
- Foreground app changes
- User interaction events

### Step 2. Grant Location Permission

When the location permission dialog appears, select:

- **While using the app**

This permission is used to support nearby device detection and Bluetooth-related event collection.

### Step 3. Grant Phone Permission

When Android asks whether AEGIS can make and manage phone calls, tap:

- **Allow**

This permission allows AEGIS to monitor call-state changes required for forensic timeline reconstruction.

### Step 4. Grant Nearby Devices Permission

When Android asks whether AEGIS can find, connect to, and determine the relative position of nearby devices, tap:

- **Allow**

This permission is required to collect Bluetooth connection, disconnection, and streaming-related events.

### Step 5. Grant Call Log Permission

When Android asks whether AEGIS can access phone call logs, tap:

- **Allow**

This permission allows AEGIS to collect call-log information for reconstructing call-related events.

### Step 6. Grant Photos and Videos Permission

When the photos and videos permission dialog appears, tap:

- **Allow all**

This permission may be required for monitoring media-related files depending on the Android version and storage policy.

### Step 7. Grant SMS Permission

When Android asks whether AEGIS can send and view SMS messages, tap:

- **Allow**

This permission is required to collect SMS send/receive events.

### Permission Summary

| Permission | Purpose |
|---|---|
| Accessibility Service | Detect UI clicks, foreground app changes, and user interactions |
| Location | Support nearby device and Bluetooth-related event collection |
| Phone | Monitor call-state changes |
| Nearby Devices | Detect Bluetooth connection, disconnection, and streaming events |
| Call Logs | Collect call-log information |
| Photos and Videos | Access media-related files for file event monitoring |
| SMS | Detect sent and received SMS events |

## Notes

AEGIS requires explicit user consent before installation and operation.

Because AEGIS monitors security-sensitive Android events, it should only be deployed in authorized environments, such as:

- Enterprise-managed Android devices
- Consent-based insurance investigation environments
- Legally approved investigation scenarios

If any permission is denied, some AEGIS functions may not operate correctly. In that case, open Android **Settings** and manually grant the missing permission.

---




### AEGIS automatically records and transmits:
1. Anti-Forensic attempts (timestamp manipulation, logcat -c, reboot/shutdown)  
2. File system events (create, modify, delete)  
3. Calling activity (incoming, outgoing, duration, rejection)  
4. SMS messages (send/receive)  
5. Bluetooth connections & streaming events  
6. App execution & UI interactions  

Generated logs are integrity-protected using **SHA-256 hash values**, and uploaded logs are verified on the server before being stored.

---

## Precautions

This tool is designed for **forensic research and investigation purposes**.

AEGIS must be used only in environments where **explicit user consent** can be obtained before installation and operation.  
Because AEGIS collects security-sensitive Android events, including calls, messages, timestamps, application execution records, file-system events, and user interaction logs, unauthorized installation or operation may cause AEGIS to function similarly to spyware.  
Such unauthorized use may result in legal, ethical, or organizational disadvantages for the operator or deploying entity.

AEGIS requires explicit installation on the target device and does **not** operate in stealth mode.  
Before using AEGIS, proper authorization, user consent, and legal compliance must be ensured.

All log data collected by AEGIS and transmitted to the server is stored in an encrypted form.  
Access to the stored log data is restricted to authorized security personnel only.  
However, even authorized security personnel do **not** have the right to decrypt encrypted log data unless a valid forensic investigation request or approved incident investigation procedure exists.

In other words, encrypted log data stored on the server must remain inaccessible for decryption during normal operation.  
Decryption and forensic analysis should only be performed under an authorized investigation process.

If you are using this tool in research or open-source projects, please cite our paper in *FSI: Digital Investigation*. ; _**(Under Review)**_

---

## Example Report Output

Each forensic report includes:
- Event type and occurrence time  
- Original log message contents  
- Tampering detection results  
- Server-device timestamp offset  
- SHA-256 hash value for integrity verification  

---

## Comparison to Existing Tools  

AEGIS provides:
- ✅ Remote storage without ADB requirement  
- ✅ Integrity verification (SHA-256)  
- ✅ Anti-Forensic detection (logcat -c, timestamp manipulation, reboot)  
- ✅ Automated timeline reconstruction  
- ✅ Structured forensic reporting  

Other tools (e.g., DroidWatch, DELTA, LogExtractor) lack one or more of these essential features.

---

## Citation

```bibtex
@article{CHO2026302176,  
   title = {AEGIS: A robust log preservation and anti-forensics resilient framework for android digital investigation},  
   journal = {Forensic Science International: Digital Investigation},  
   volume = {58},  
   pages = {302176},  
   year = {2026},  
   issn = {2666-2817},  
   doi = {https://doi.org/10.1016/j.fsidi.2026.302176},  
   url = {https://www.sciencedirect.com/science/article/pii/S2666281726001332},  
   author = {Minhyuk Cho and Seungmin Lee and Sungwon Jeong and Seong-je Cho and Minkyu Park}  
}  
```

<!--If you use AEGIS in your research, please cite:

```bibtex
@article{choa2025aegis,
  title={AEGIS: Android Evidence Guard tool for Integrity and Survival},
  author={Minhyuk Cho and Seungmin Lee and Sungwon Jeong and Seong-je Cho and Minkyu Park},
  journal={FSI: Digital Investigation},
  year={2025}
}
```-->    

