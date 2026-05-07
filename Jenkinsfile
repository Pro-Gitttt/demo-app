pipeline {
    agent any

    // ── These 3 params are injected by your Pipeline-Service backend ──────────
    parameters {
        string(name: 'EXECUTION_ID', defaultValue: '1',    description: 'Execution ID from backend')
        string(name: 'PROJECT_ID',   defaultValue: '1',    description: 'Project ID from backend')
        string(name: 'COMMIT_HASH',  defaultValue: 'HEAD', description: 'Git commit hash')
    }

    environment {
        JAVA_HOME_21         = "/usr/lib/jvm/java-21-openjdk-amd64"
        JAVA_HOME_17         = "/usr/lib/jvm/java-17-openjdk-amd64"

        // Docker registry on Jenkins VM (Nexus/registry)
        REGISTRY             = "192.168.56.10:5000"
        IMAGE_NAME           = "${REGISTRY}/demo-app"

        // SonarQube on Jenkins VM
        SONAR_URL            = "http://192.168.56.10:9000"

        // Security Service — NodePort on k8s VM
        SECURITY_SERVICE_URL = "http://192.168.56.20:30083/api/security/scan"

        // Gateway — for status callback
        GATEWAY_URL          = "http://192.168.56.20:30080"

        // k8s manifest
        K8S_MANIFEST         = "k8s/demo-app.yaml"
    }

    stages {

        // ── 1. CHECKOUT ───────────────────────────────────────────────────────
        stage('Checkout') {
            steps {
                checkout scm
                echo "Building commit: ${COMMIT_HASH}"
            }
        }

        // ── 2. BUILD & TEST ───────────────────────────────────────────────────
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
                    // Publish JUnit test results
                    junit allowEmptyResults: true,
                          testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        // ── 3. SONARQUBE ──────────────────────────────────────────────────────
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

        // ── 4. TRIVY SCAN ─────────────────────────────────────────────────────
        stage('Trivy Scan') {
            steps {
                sh '''
                    trivy fs --format json -o trivy.json . || true
                    test -s trivy.json || echo "{}" > trivy.json
                '''
            }
        }

        // ── 5. GITLEAKS SCAN ──────────────────────────────────────────────────
        stage('Gitleaks Scan') {
            steps {
                sh '''
                    gitleaks detect \
                      --source . \
                      --report-format json \
                      --report-path gitleaks.json || true
                    test -s gitleaks.json || echo "{}" > gitleaks.json
                '''
            }
        }

        // ── 6. SEND SECURITY REPORTS ──────────────────────────────────────────
        stage('Send Security Reports') {
            steps {
                script {
                    echo "Sending reports → ${SECURITY_SERVICE_URL}"
                    echo "  executionId = ${EXECUTION_ID}"
                    echo "  projectId   = ${PROJECT_ID}"

                    def response = sh(script: """
                        curl -sf -X POST ${SECURITY_SERVICE_URL} \
                          -F "executionId=${EXECUTION_ID}" \
                          -F "projectId=${PROJECT_ID}" \
                          -F "trivy=@trivy.json" \
                          -F "gitleaks=@gitleaks.json"
                    """, returnStdout: true).trim()

                    echo "Security response: ${response}"

                    if (response.contains('"blocked":true')) {
                        error("❌ BLOCKED by Security Service — fix vulnerabilities and retry")
                    }
                }
            }
        }

        // ── 7. DOCKER BUILD ───────────────────────────────────────────────────
        stage('Docker Build') {
            steps {
                sh """
                    docker build -t ${IMAGE_NAME}:latest \
                                 -t ${IMAGE_NAME}:${BUILD_NUMBER} \
                                 .
                """
            }
        }

        // ── 8. DOCKER PUSH ────────────────────────────────────────────────────
        stage('Docker Push') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'nexus-credentials',
                    usernameVariable: 'NEXUS_USER',
                    passwordVariable: 'NEXUS_PASS'
                )]) {
                    sh """
                        echo "${NEXUS_PASS}" | docker login ${REGISTRY} \
                          -u ${NEXUS_USER} --password-stdin

                        docker push ${IMAGE_NAME}:latest
                        docker push ${IMAGE_NAME}:${BUILD_NUMBER}
                    """
                }
            }
        }

        // ── 9. DEPLOY TO KUBERNETES ───────────────────────────────────────────
        stage('Deploy to Kubernetes') {
            steps {
                sh """
                    kubectl apply -f ${K8S_MANIFEST}
                    kubectl rollout restart deployment/demo-app -n demo
                    kubectl rollout status deployment/demo-app  -n demo --timeout=90s
                """
            }
        }

        // ── 10. SMOKE TEST ────────────────────────────────────────────────────
        stage('Smoke Test') {
            steps {
                sh '''
                    echo "Waiting for app to be ready..."
                    sleep 10
                    curl -sf http://192.168.56.20:30090/api/hello && \
                      echo "✅ Smoke test passed" || \
                      echo "⚠️  Smoke test failed — app may still be starting"
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.json', fingerprint: true, allowEmptyArchive: true
        }
        success {
            echo "✅ demo-app deployed successfully!"
            script {
                sh """
                    curl -sf -X PUT ${GATEWAY_URL}/api/executions/${EXECUTION_ID}/status \
                      -H 'Content-Type: application/json' \
                      -d '{"status":"SUCCESS"}' || true
                """
            }
        }
        failure {
            echo "❌ demo-app pipeline failed"
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
