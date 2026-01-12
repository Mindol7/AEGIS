# AEGIS: Android Evidence Guard tool for Integrity and Survival

Developer : Minhyuk Cho, Seungmin Lee, Sungwon Jeong, Seong-je Cho, Minkyu Park in Computer SEcurity & OS (CSOS) Lab, Dankook University  
Contact Mail : cgumgek8@gmail.com  
<!--Paper : FSI: Digital Investigation (Preprint, 2025)  -->  
Tool Name : AEGIS  

---

## Usage

### Command (Android)

```bash
Install AEGIS.apk on target Android device
Logs will be generated and stored in protected internal storage
Collected logs are transmitted to Remote Server (Spring Boot + MongoDB)
Forensic Analyzer reconstructs timeline and generates report
```

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
It requires explicit installation on the target device (no stealth mode).  
The captured data includes sensitive personal information (calls, messages, timestamps), so proper authorization and legal compliance must be ensured.  

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

We are currently writing a research manuscript on AEGIS.
After submission, we will announce the preprint and kindly request that it be cited in related work.  


If you use AEGIS in your research, please cite:

<!-- ```bibtex
@article{choa2025aegis,
  title={AEGIS: Android Evidence Guard tool for Integrity and Survival},
  author={Minhyuk Cho and Seungmin Lee and Sungwon Jeong and Seong-je Cho and Minkyu Park},
  journal={FSI: Digital Investigation},
  year={2025}
}
```-->    

## Acknowledgement

+ This work was supported by Basic Science Research Program through the National Research Foundation of Korea (NRF) funded by the Ministry of Science and ICT (No. 2021R1A2C2012574).  
![시그니처 가로형_영문조합형](https://user-images.githubusercontent.com/64211521/80204259-7e798980-8663-11ea-95f1-ff19ccb86a77.jpg)
