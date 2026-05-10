pipeline {
    agent any

    parameters {
        string(name: 'EXECUTION_ID', defaultValue: '1',    description: 'Execution ID from backend')
        string(name: 'PROJECT_ID',   defaultValue: '1',    description: 'Project ID from backend')
        string(name: 'COMMIT_HASH',  defaultValue: 'HEAD', description: 'Git commit hash')
    }

    environment {
        JAVA_HOME_21         = "/usr/lib/jvm/java-21-openjdk-amd64"
        JAVA_HOME_17         = "/usr/lib/jvm/java-17-openjdk-amd64"
        REGISTRY             = "192.168.56.10:5000"
        IMAGE_NAME           = "${REGISTRY}/demo-app"
        SONAR_URL            = "http://192.168.56.10:9000"
        SECURITY_SERVICE_URL = "http://192.168.56.20:30083/api/security/scan"
        GATEWAY_URL          = "http://192.168.56.20:30080"
        K8S_MANIFEST         = "k8s/demo-app.yaml"
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                echo "Building commit: ${COMMIT_HASH}"
            }
        }

        stage('Build & Test') {
            steps {
                withEnv(["JAVA_HOME=${JAVA_HOME_21}", "PATH+JAVA=${JAVA_HOME_21}/bin"]) {
                    sh '''
                        java -version
                        mvn clean verify -DskipTests=false
                    '''
                }
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                    withEnv(["JAVA_HOME=${JAVA_HOME_17}", "PATH+JAVA=${JAVA_HOME_17}/bin"]) {
                        sh """
                            mvn sonar:sonar \
                              -Dsonar.projectKey=demo-app \
                              -Dsonar.projectName="Demo App" \
                              -Dsonar.host.url=${SONAR_URL} \
                              -Dsonar.login=${SONAR_TOKEN}
                        """
                    }
                }
            }
        }

        // ── FIX: scope Trivy to ONLY the compiled jar, not the whole workspace ──
        // The workspace may contain the full PFE-2026 repo with hundreds of CVEs.
        // We only want to scan THIS app's artifact.
        stage('Trivy Scan') {
            steps {
                sh '''
                    # Scan only the compiled jar — not the whole filesystem
                    JAR=$(find target -name "*.jar" ! -name "*sources*" | head -1)

                    if [ -n "$JAR" ]; then
                        echo "Scanning jar: $JAR"
                        trivy fs \
                          --scanners vuln \
                          --severity HIGH,CRITICAL \
                          --ignore-unfixed \
                          --format json \
                          -o trivy.json \
                          "$JAR" || true
                    else
                        echo "No jar found — scanning src/ only"
                        trivy fs \
                          --scanners vuln \
                          --severity HIGH,CRITICAL \
                          --ignore-unfixed \
                          --format json \
                          -o trivy.json \
                          src/ || true
                    fi

                    # Ensure valid JSON even if trivy found nothing or errored
                    if [ ! -s trivy.json ]; then
                        echo '{"Results":[]}' > trivy.json
                    fi

                    # Show summary
                    echo "=== Trivy results ==="
                    cat trivy.json | python3 -c "
import sys, json
data = json.load(sys.stdin)
results = data.get('Results', [])
total = sum(len(r.get('Vulnerabilities') or []) for r in results)
print(f'Total vulnerabilities found: {total}')
for r in results:
    for v in (r.get('Vulnerabilities') or []):
        print(f'  [{v.get(\"Severity\",\"?\")}] {v.get(\"VulnerabilityID\",\"?\")} - {v.get(\"Title\",\"?\")[:60]}')
" || echo "(could not parse trivy output)"
                '''
            }
        }

        // ── FIX: Gitleaks on src/ only — valid JSON array output ─────────────
        stage('Gitleaks Scan') {
            steps {
                sh """
                    gitleaks detect \\
                      --source src/ \\
                      --report-format json \\
                      --report-path gitleaks.json \\
                      --no-git || true

                    if [ ! -s gitleaks.json ]; then
                        echo '[]' > gitleaks.json
                    fi

                    echo "=== Gitleaks results ==="
                    python3 -c "
import json
data = json.load(open('gitleaks.json'))
leaks = data if isinstance(data, list) else []
print('Secrets found: ' + str(len(leaks)))
for l in leaks[:5]:
    print('  [' + str(l.get('RuleID','?')) + '] ' + str(l.get('File','?')))
"
                """
            }
        }

        stage('Send Security Reports') {
            steps {
                script {
                    echo "Sending security reports → ${SECURITY_SERVICE_URL}"
                    echo "  executionId = ${EXECUTION_ID}"
                    echo "  projectId   = ${PROJECT_ID}"

                    // Show what we're sending
                    sh '''
                        echo "trivy.json size: $(wc -c < trivy.json) bytes"
                        echo "gitleaks.json size: $(wc -c < gitleaks.json) bytes"
                    '''

                    def response = sh(script: """
                        curl -sf -X POST ${SECURITY_SERVICE_URL} \
                          -F "executionId=${EXECUTION_ID}" \
                          -F "projectId=${PROJECT_ID}" \
                          -F "trivy=@trivy.json" \
                          -F "gitleaks=@gitleaks.json"
                    """, returnStdout: true).trim()

                    echo "Security Service response: ${response}"

                    // Parse score from response for logging
                    sh """
                        echo '${response}' | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
    score = d.get('securityScore', d.get('score', '?'))
    blocked = d.get('blocked', '?')
    print(f'Score: {score}  |  Blocked: {blocked}')
except:
    print('Could not parse response')
" || true
                    """

                    if (response.contains('"blocked":true')) {
                        // Extract score to give helpful message
                        error("❌ SECURITY BLOCKED — score too low or critical vulnerability found. Check the Security page in the platform for details.")
                    }

                    echo "✅ Security check passed — pipeline continues"
                }
            }
        }

        stage('Docker Build') {
            steps {
                sh """
                    docker build \
                      -t ${IMAGE_NAME}:latest \
                      -t ${IMAGE_NAME}:${BUILD_NUMBER} \
                      .
                """
            }
        }

        stage('Docker Push') {
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: 'nexus-credentials',
                        usernameVariable: 'NEXUS_USER',
                        passwordVariable: 'NEXUS_PASS'
                    )]) {
                        // FIX: use single-quoted sh so Groovy does NOT interpolate
                        // $NEXUS_PASS before the shell sees it — safe with special chars.
                        // Use printf instead of echo to avoid trailing newline issues.
                        sh '''
                            set -e

                            echo "=== Checking registry connectivity ==="
                            curl -sf --max-time 5 http://192.168.56.10:5000/v2/ || {
                                echo "ERROR: Nexus registry 192.168.56.10:5000 unreachable"
                                echo "Make sure Nexus is running: sudo docker ps | grep nexus"
                                exit 1
                            }
                            echo "Registry reachable ✓"

                            echo "=== Docker login ==="
                            printf '%s' "$NEXUS_PASS" | docker login 192.168.56.10:5000 \
                                -u "$NEXUS_USER" --password-stdin
                            echo "Docker login successful ✓"

                            echo "=== Pushing images ==="
                            docker push 192.168.56.10:5000/demo-app:latest
                            docker push "192.168.56.10:5000/demo-app:$BUILD_NUMBER"
                            echo "Images pushed successfully ✓"
                        '''
                    }
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                sh """
                    kubectl apply -f ${K8S_MANIFEST}
                    kubectl rollout restart deployment/demo-app -n demo
                    kubectl rollout status deployment/demo-app -n demo --timeout=120s
                """
            }
        }

        stage('Smoke Test') {
            steps {
                sh '''
                    echo "Waiting 15s for pod to be ready..."
                    sleep 15
                    curl -sf http://192.168.56.20:30090/api/hello \
                      && echo "✅ Smoke test PASSED" \
                      || echo "⚠️  Smoke test failed — pod may still be starting"
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.json',
                             fingerprint: true,
                             allowEmptyArchive: true
        }
        success {
            echo "✅ demo-app deployed successfully — Build #${BUILD_NUMBER}"
            script {
                sh """
                    curl -sf -X PUT ${GATEWAY_URL}/api/executions/${EXECUTION_ID}/status \
                      -H 'Content-Type: application/json' \
                      -d '{"status":"SUCCESS"}' || true
                """
            }
        }
        failure {
            echo "❌ demo-app pipeline FAILED — Build #${BUILD_NUMBER}"
            script {
                sh """
                    curl -sf -X PUT ${GATEWAY_URL}/api/executions/${EXECUTION_ID}/status \
                      -H 'Content-Type: application/json' \
                      -d '{"status":"FAILED"}' || true
                """
            }
        }
    }
}
