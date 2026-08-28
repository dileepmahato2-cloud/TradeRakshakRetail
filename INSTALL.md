# TradeRakshak™ v11.5 — Installation Guide (No Docker Required)

## What's New in This Version
- **No Docker Desktop required** — runs directly with Java
- **No system Java install required** — portable JDK downloads automatically
- **No Node.js or Maven install required** — everything downloads automatically
- Stock Ranking Engine (11-parameter scoring)
- Support & Resistance widget (Sahi logic, 4 indices)

---

## Minimum Requirements
| Item | Requirement |
|------|------------|
| OS | Windows 10/11 (64-bit) |
| RAM | 4 GB (8 GB recommended) |
| Disk | 2 GB free space |
| Internet | Required for first-time setup only |

---

## Windows — First Time Installation

1. **Extract** the ZIP file to any folder (e.g. `E:\Share Market\TradeRakshak\`)
2. **Double-click** `INSTALL.bat`
3. The installer will:
   - Download Java 21 automatically (one-time, into this folder only)
   - Download and build the application (~80 MB, one-time)
   - Start the application automatically
4. **Wait** for "Started TradeRakshakApplication" message
5. **Open browser** at: **http://localhost:8080**

### Login
- Email: `superadmin@traderakshak.local`
- Password: `Admin@123`

---

## Day-to-Day Use (After First Install)

| Action | Windows |
|--------|---------|
| Start app | Double-click `START.bat` |
| Stop app | Double-click `STOP.bat` or Ctrl+C |
| Access | http://localhost:8080 |

**Starting takes 10–20 seconds** (not minutes — first install was the slow part).

---

## Linux / Mac

```bash
chmod +x install.sh
bash install.sh
```

Then start with `bash start.sh`.

---

## Data & Settings

| Item | Location |
|------|---------|
| Database | `data\app.db` |
| Uploaded files | `uploads\` |
| Exports | `exports\` |
| Settings | `.env` |
| Java (portable) | `jdk21\` |
| Built app | `backend\target\*.jar` |

**Your data is never sent anywhere.** Everything stays in this folder.

---

## Upgrade from Previous Version

Copy your `data\` folder and `.env` file to the new version folder, then run `INSTALL.bat`.  
Database migrations run automatically — no data loss.

---

## Troubleshooting

| Problem | Solution |
|---------|---------|
| "Java not found" | INSTALL.bat downloads it automatically |
| Build fails | Check internet, free up disk space, retry |
| Port 8080 busy | Edit `.env`, change `PORT=8081`, restart |
| App doesn't open | Wait 30 sec, try http://localhost:8080 manually |
| "Antivirus blocked" | Add the folder to antivirus exclusions |

---

## Technical Architecture

```
INSTALL.bat
    │
    ├── Downloads portable JDK 21 → jdk21\  (if not present)
    ├── Maven builds: Angular + Spring Boot → single JAR
    │       Angular static files → backend/src/main/resources/static/
    │       Spring Boot packages → backend/target/traderakshak-backend-11.5.0.jar
    │
    └── Starts: java -jar traderakshak-backend-11.5.0.jar
                    │
                    ├── Serves Angular frontend at  http://localhost:8080/
                    ├── Serves REST API at          http://localhost:8080/api/
                    ├── SQLite database at          data/app.db
                    └── Flyway auto-migrates DB on start
```

The single JAR contains everything: frontend, backend, and database driver.
