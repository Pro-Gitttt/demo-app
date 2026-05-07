# demo-app — DevSecOps Test Project

A minimal Spring Boot REST app to test your full DevSecOps pipeline end-to-end.

---

## What this is

- **Spring Boot 3.2** REST app (Java 21)
- 3 endpoints: `GET /api/hello`, `GET /api/version`, `POST /api/echo`
- JUnit tests + JaCoCo coverage (for SonarQube)
- Dockerfile (multi-stage, non-root user)
- Kubernetes manifest (namespace: `demo`, NodePort: `30090`)
- Jenkinsfile that matches your backend's `EXECUTION_ID` / `PROJECT_ID` / `COMMIT_HASH` params

---

## Step 1 — Push to Gitea

```bash
# On your machine or Jenkins VM
cd demo-app
git init
git add .
git commit -m "Initial demo-app"

# Push to your Gitea (running on Jenkins VM)
git remote add origin http://192.168.56.10:3000/YOUR_USER/demo-app.git
git push -u origin main
```

---

## Step 2 — Create Jenkins Pipeline Job

1. Open Jenkins → `http://192.168.56.10:8080`
2. **New Item** → name it **`demo-app-pipeline`** → choose **Pipeline**
3. Under **Pipeline**:
   - Definition: **Pipeline script from SCM**
   - SCM: **Git**
   - Repository URL: `http://192.168.56.10:3000/YOUR_USER/demo-app.git`
   - Branch: `*/main`
   - Script Path: `Jenkinsfile`
4. Check **"This project is parameterized"** → add:
   - `EXECUTION_ID` (String, default: `1`)
   - `PROJECT_ID` (String, default: `1`)
   - `COMMIT_HASH` (String, default: `HEAD`)
5. **Save**

---

## Step 3 — Create Project in your DevSecOps UI

1. Log in to your frontend → `http://192.168.56.20:30080` (through gateway) or `http://localhost:4200`
2. Go to **Projects** → **New Project**
3. Fill in:
   | Field | Value |
   |-------|-------|
   | Name | `demo-app` |
   | Repository URL | `http://192.168.56.10:3000/YOUR_USER/demo-app.git` |
   | Branch | `main` |
   | VCS Type | `GITHUB` (or GITLAB — Gitea is compatible) |
4. **Save**

---

## Step 4 — Deploy from the UI

1. On the Projects page, find **demo-app** → click **Deploy**
2. The modal will detect no pipeline exists → click **"+ Créer le pipeline"**
3. Fill in:
   | Field | Value |
   |-------|-------|
   | Pipeline Name | `demo-app-pipeline` |
   | Jenkins Job Name | `demo-app-pipeline` ← must match exactly what you named in Step 2 |
4. Click **Créer le pipeline**
5. Now select the pipeline, choose environment `DEV`, commit `HEAD`
6. Click **Confirmer le déploiement**

Your backend will:
- Create a `PipelineExecution` record
- Call Jenkins `/job/demo-app-pipeline/buildWithParameters` with `EXECUTION_ID`, `PROJECT_ID`, `COMMIT_HASH`
- Poll every 4s for status updates
- Jenkins will call back `PUT /api/executions/{id}/status` when done

---

## Step 5 — Verify

```bash
# Check pod is running
kubectl get pods -n demo

# Test the app
curl http://192.168.56.20:30090/api/hello
curl http://192.168.56.20:30090/api/version
```

Expected response from `/api/hello`:
```json
{
  "message": "Hello from Demo App!",
  "status": "UP",
  "time": "2026-05-05T..."
}
```

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/hello` | Health/greeting |
| GET | `/api/version` | App version + environment |
| POST | `/api/echo` | Echo request body |
| GET | `/actuator/health` | Spring Boot health (k8s probes) |

---

## Architecture

```
Jenkins VM (192.168.56.10)
  ├── Jenkins :8080          ← runs the pipeline
  ├── SonarQube :9000        ← code quality
  ├── Gitea :3000            ← git repository
  └── Nexus/Registry :5000   ← docker images

Kubernetes VM (192.168.56.20)
  ├── Gateway :30080         ← your DevSecOps frontend+backend
  ├── Security Service :30083 ← receives Trivy+Gitleaks reports
  └── demo-app :30090        ← this app after deployment
```
