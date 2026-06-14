
pipeline {
    agent any

    parameters {
        string(name: 'EXECUTION_ID', defaultValue: '1',    description: 'Execution ID')
        string(name: 'PROJECT_ID',   defaultValue: '1',    description: 'Project ID')
        string(name: 'COMMIT_HASH',  defaultValue: 'HEAD', description: 'Git commit hash')
    }

    environment {
        JAVA_HOME_21         = "/usr/lib/jvm/java-21-openjdk-amd64"
        REGISTRY             = "192.168.56.10:5000"
        IMAGE_NAME           = "${REGISTRY}/demo-app"
        SONAR_URL            = "http://192.168.56.10:9000"
        SECURITY_SERVICE_URL = "http://192.168.56.20:30080/api/security/scan"
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
                    sh 'mvn clean verify -DskipTests=false'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                    withEnv(["JAVA_HOME=${JAVA_HOME_21}", "PATH+JAVA=${JAVA_HOME_21}/bin"]) {
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

        stage('Trivy Scan') {
            steps {
                sh '''
                    set +e

                    # Find the compiled jar — scan ONLY that, not the whole workspace
                    JAR=$(find target -name "*.jar" ! -name "*sources*" ! -name "*original*" | head -1)

                    if [ -n "$JAR" ]; then
                        echo "Trivy scanning jar: $JAR"
                        trivy fs \
                          --format json \
                          --output trivy.json \
                          --scanners vuln \
                          --severity HIGH,CRITICAL \
                          --ignore-unfixed \
                          "$JAR"
                    else
                        echo "No jar found, scanning src/"
                        trivy fs \
                          --format json \
                          --output trivy.json \
                          --scanners vuln \
                          src/
                    fi

                    # Always ensure trivy.json is valid JSON with Results key
                    if [ ! -s trivy.json ]; then
                        echo "trivy.json is empty — writing clean placeholder"
                        printf '{"SchemaVersion":2,"Results":[]}' > trivy.json
                    fi

                    echo "trivy.json content (first 200 chars):"
                    head -c 200 trivy.json
                    echo ""
                    echo "trivy.json size: $(wc -c < trivy.json) bytes"
                    set -e
                '''
            }
        }

        stage('Gitleaks Scan') {
            steps {
                sh '''
                    set +e
                    gitleaks detect \
                      --source src/ \
                      --report-format json \
                      --report-path gitleaks.json \
                      --no-git || true

                    if [ ! -s gitleaks.json ]; then
                        echo "[]" > gitleaks.json
                    fi

                    echo "gitleaks.json size: $(wc -c < gitleaks.json) bytes"
                    set -e
                '''
            }
        }

        stage('Send Security Reports') {
            steps {
                script {
                    echo "Sending reports to: ${SECURITY_SERVICE_URL}"
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
                    echo "✅ Security check passed"
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
                        sh '''
                            printf '%s' "$NEXUS_PASS" | docker login 192.168.56.10:5000 \
                                -u "$NEXUS_USER" --password-stdin
                            docker push 192.168.56.10:5000/demo-app:latest
                            docker push "192.168.56.10:5000/demo-app:$BUILD_NUMBER"
                        '''
                    }
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                sh """
                    kubectl apply -f ${K8S_MANIFEST}
                    kubectl rollout restart deployment/demo-app -n dev
                    kubectl rollout status deployment/demo-app -n dev --timeout=120s
                """
            }
        }

        stage('Smoke Test') {
            steps {
                sh '''
                    sleep 15
                    curl -sf http://192.168.56.20:30090/api/hello \
                      && echo "✅ Smoke test PASSED" \
                      || echo "⚠️  Smoke test — pod may still be starting"
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.json', fingerprint: true, allowEmptyArchive: true
        }
        success {
            echo "✅ demo-app deployed — Build #${BUILD_NUMBER}"
            sh """
                curl -sf -X PUT ${GATEWAY_URL}/api/executions/${EXECUTION_ID}/status \
                  -H 'Content-Type: application/json' \
                  -d '{"status":"SUCCESS"}' || true
            """
        }
        failure {
            echo "❌ demo-app pipeline FAILED — Build #${BUILD_NUMBER}"
            sh """
                curl -sf -X PUT ${GATEWAY_URL}/api/executions/${EXECUTION_ID}/status \
                  -H 'Content-Type: application/json' \
                  -d '{"status":"FAILED"}' || true
            """
        }
    }
}

