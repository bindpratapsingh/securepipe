// SecurePipe - CI/CD pipeline for the VRSMS Spring Boot backend.
//
// Flow:
//   Build & Unit Test  ->  SonarQube  ->  Quality Gate  ->  Docker Build
//                      ->  Trivy Scan ->  Helm Deploy   ->  Smoke Test
//
// The Trivy stage is the hard gate: a CRITICAL vulnerability stops the build
// before anything is deployed. The SonarQube gate is advisory by default -
// see the Quality Gate stage for why.

pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'FAIL_ON_CRITICAL',
            defaultValue: true,
            description: 'Stop the build when Trivy finds a CRITICAL vulnerability in the image. This is the security gate; leave it on.'
        )
        booleanParam(
            name: 'IGNORE_UNFIXED',
            defaultValue: true,
            description: 'Ignore CVEs that have no vendor patch yet. On means you are only gating on things you can actually act on.'
        )
        string(
            name: 'DOCKERFILE',
            defaultValue: 'Dockerfile',
            description: 'Which Dockerfile to build. Set to Dockerfile.vulnerable to watch the Trivy gate stop the pipeline.'
        )
        booleanParam(
            name: 'FAIL_ON_QUALITY_GATE',
            defaultValue: false,
            description: 'Treat a failed SonarQube quality gate as a build failure rather than just marking it unstable.'
        )
        booleanParam(
            name: 'RUN_SONAR',
            defaultValue: true,
            description: 'Run the SonarQube analysis stage. Turn off for a faster feedback loop while working on other stages.'
        )
        booleanParam(
            name: 'DEPLOY',
            defaultValue: true,
            description: 'Deploy to minikube with Helm. Turn off if the cluster is not running.'
        )
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        APP_DIR         = 'app'
        IMAGE_NAME      = 'vrsms-server'
        IMAGE_TAG       = "${env.BUILD_NUMBER}"
        HELM_RELEASE    = 'vrsms'
        K8S_NAMESPACE   = 'vrsms'
        SONAR_PROJECT   = 'vrsms-securepipe'
        // Persisted in the jenkins_home volume so the ~60 MB vulnerability
        // database is not re-downloaded on every build.
        TRIVY_CACHE_DIR = '/var/jenkins_home/trivy-cache'
    }

    stages {

        stage('Checkout') {
            steps {
                script {
                    def commit = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${commit}"
                    echo "Building ${env.IMAGE_NAME}:${env.IMAGE_TAG} from commit ${commit}"
                    echo "Dockerfile: ${params.DOCKERFILE}"
                }
            }
        }

        stage('Build & Unit Test') {
            steps {
                dir(env.APP_DIR) {
                    // "verify" compiles, runs surefire, and writes the JaCoCo
                    // XML report that the SonarQube stage uploads.
                    //
                    // The pom excludes the "integration" and "ui" JUnit tags:
                    // the integration test needs a live PostgreSQL and the UI
                    // test drives a real Chrome browser, neither of which
                    // exists on a CI agent.
                    sh './mvnw -B -ntp clean verify'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'app/target/surefire-reports/*.xml'
                    jacoco execPattern:   'app/target/jacoco.exec',
                           classPattern:  'app/target/classes',
                           sourcePattern: 'app/src/main/java'
                }
            }
        }

        stage('SonarQube Analysis') {
            when { expression { params.RUN_SONAR } }
            steps {
                // withSonarQubeEnv injects SONAR_HOST_URL and SONAR_AUTH_TOKEN
                // from the "SonarQube" server defined in ci/jenkins/casc.yaml,
                // so no URL or token is hardcoded here.
                withSonarQubeEnv('SonarQube') {
                    dir(env.APP_DIR) {
                        sh """
                            ./mvnw -B -ntp sonar:sonar \
                              -Dsonar.projectKey=${SONAR_PROJECT} \
                              -Dsonar.projectName='VRSMS SecurePipe'
                        """
                    }
                }
            }
        }

        stage('Quality Gate') {
            when { expression { params.RUN_SONAR } }
            steps {
                script {
                    // waitForQualityGate blocks until SonarQube calls back on
                    // its webhook (created by scripts/01-start-toolchain.sh).
                    // The timeout means a missing webhook fails fast instead
                    // of hanging the build for 45 minutes.
                    //
                    // This gate is advisory by default. SonarQube's built-in
                    // "Sonar way" gate judges *new* code, and on a first
                    // analysis the entire pre-existing codebase counts as new,
                    // so its 80%-coverage condition fails every build until
                    // the test suite catches up. Flip FAIL_ON_QUALITY_GATE on
                    // once the gate is tuned for this codebase.
                    def status = 'UNKNOWN'
                    try {
                        timeout(time: 10, unit: 'MINUTES') {
                            status = waitForQualityGate(abortPipeline: false).status
                        }
                    } catch (err) {
                        echo "Could not read the quality gate: ${err.getMessage()}"
                        echo 'Check that the SonarQube webhook points at http://jenkins:8080/sonarqube-webhook/'
                        status = 'UNAVAILABLE'
                    }

                    echo "SonarQube quality gate: ${status}"
                    if (status != 'OK') {
                        if (params.FAIL_ON_QUALITY_GATE) {
                            error "Quality gate did not pass: ${status}"
                        }
                        unstable "Quality gate did not pass: ${status} (advisory, not blocking)"
                    }
                }
            }
        }

        stage('Docker Build') {
            steps {
                dir(env.APP_DIR) {
                    // The jar was already built in the test stage, so the
                    // Dockerfile only copies it in. .dockerignore keeps the
                    // build context down to that single jar.
                    sh """
                        docker build \
                          -f ${params.DOCKERFILE} \
                          -t ${IMAGE_NAME}:${IMAGE_TAG} \
                          -t ${IMAGE_NAME}:latest \
                          .
                    """
                    sh "docker image inspect ${IMAGE_NAME}:${IMAGE_TAG} --format 'Image size: {{.Size}} bytes'"
                }
            }
        }

        stage('Trivy Scan') {
            steps {
                script {
                    def ignoreUnfixed = params.IGNORE_UNFIXED ? '--ignore-unfixed' : ''
                    def common = "--cache-dir ${env.TRIVY_CACHE_DIR} --no-progress --ignorefile ci/trivy/.trivyignore"

                    sh "mkdir -p ${env.TRIVY_CACHE_DIR} reports"

                    // Pass 1: full inventory at every severity. Never fails the
                    // build - this is the artifact a human reads afterwards.
                    sh """
                        trivy image ${common} \
                          --severity LOW,MEDIUM,HIGH,CRITICAL \
                          --format table \
                          --output reports/trivy-report.txt \
                          ${IMAGE_NAME}:${IMAGE_TAG} || true
                    """
                    sh """
                        trivy image ${common} \
                          --severity LOW,MEDIUM,HIGH,CRITICAL \
                          --format template \
                          --template @/usr/local/share/trivy/templates/html.tpl \
                          --output reports/trivy-report.html \
                          ${IMAGE_NAME}:${IMAGE_TAG} || true
                    """
                    sh 'echo "--- vulnerability summary ---"; tail -n 40 reports/trivy-report.txt || true'

                    // Pass 2: the gate. --exit-code 1 makes Trivy itself signal
                    // the failure, which is what stops the pipeline here,
                    // before the deploy stage can run.
                    def code = sh(returnStatus: true, script: """
                        trivy image ${common} \
                          ${ignoreUnfixed} \
                          --severity CRITICAL \
                          --exit-code 1 \
                          ${IMAGE_NAME}:${IMAGE_TAG}
                    """)

                    if (code != 0) {
                        def msg = "Trivy found CRITICAL vulnerabilities in ${env.IMAGE_NAME}:${env.IMAGE_TAG}"
                        if (params.FAIL_ON_CRITICAL) {
                            error "${msg}. Blocking the deploy. See the Trivy report artifact for details."
                        }
                        unstable "${msg}. FAIL_ON_CRITICAL is off, so continuing anyway."
                    } else {
                        echo 'No CRITICAL vulnerabilities. Security gate passed.'
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'reports/trivy-report.*',
                                     allowEmptyArchive: true,
                                     fingerprint: false
                    publishHTML target: [
                        reportDir:            'reports',
                        reportFiles:          'trivy-report.html',
                        reportName:           'Trivy Vulnerability Report',
                        keepAll:              true,
                        alwaysLinkToLastBuild: true,
                        allowMissing:         true
                    ]
                }
            }
        }

        stage('Helm Deploy') {
            when { expression { params.DEPLOY } }
            steps {
                sh 'kubectl cluster-info'

                // minikube runs its own container runtime, so an image built on
                // the host daemon is invisible to the cluster until it is loaded
                // in. The chart sets imagePullPolicy: IfNotPresent so Kubernetes
                // uses this local copy instead of trying Docker Hub.
                sh """
                    minikube image load ${IMAGE_NAME}:${IMAGE_TAG} \
                      || docker save ${IMAGE_NAME}:${IMAGE_TAG} | docker exec -i minikube docker load
                """

                sh """
                    helm upgrade --install ${HELM_RELEASE} charts/vrsms \
                      --namespace ${K8S_NAMESPACE} --create-namespace \
                      --set image.repository=${IMAGE_NAME} \
                      --set image.tag=${IMAGE_TAG} \
                      --wait --timeout 6m
                """

                sh "helm status ${HELM_RELEASE} --namespace ${K8S_NAMESPACE}"
                sh "kubectl get all --namespace ${K8S_NAMESPACE}"
            }
        }

        stage('Smoke Test') {
            when { expression { params.DEPLOY } }
            steps {
                // Uses the curl already present in the app image, so nothing
                // extra is pulled into the cluster.
                sh """
                    kubectl --namespace ${K8S_NAMESPACE} \
                      exec deploy/${HELM_RELEASE} -- \
                      curl -fsS http://localhost:8080/actuator/health
                """
                echo 'Deployed application reports healthy.'
            }
        }
    }

    post {
        failure {
            echo 'Pipeline failed. If it stopped at Trivy Scan, that is the security gate doing its job.'
        }
        always {
            script {
                echo "Result: ${currentBuild.currentResult}"
            }
        }
    }
}
