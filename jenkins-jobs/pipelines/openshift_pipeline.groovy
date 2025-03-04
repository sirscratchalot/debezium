pipeline {
    agent {
        label 'Slave'
    }

    stages {
        stage('Checkout - Debezium') {
            steps {
                checkout([
                        $class           : 'GitSCM',
                        branches         : [[name: "${DBZ_GIT_BRANCH}"]],
                        userRemoteConfigs: [[url: "${DBZ_GIT_REPOSITORY}"]],
                        extensions       : [[$class           : 'RelativeTargetDirectory',
                                             relativeTargetDir: 'debezium']],
                ])
            }
        }

        stage('Checkout - Upstream Strimzi') {
            when {
                expression { !params.PRODUCT_BUILD }
            }
            steps {
                checkout([
                        $class           : 'GitSCM',
                        branches         : [[name: "${STRZ_GIT_BRANCH}"]],
                        userRemoteConfigs: [[url: "${STRZ_GIT_REPOSITORY}"]],
                        extensions       : [[$class           : 'RelativeTargetDirectory',
                                             relativeTargetDir: 'strimzi']],
                ])
                script {
                    env.STRZ_RESOURCES = "${env.WORKSPACE}/strimzi/install/cluster-operator"
                }
            }
        }

        stage('Checkout - Downstream AMQ Streams') {
            when {
                expression { params.PRODUCT_BUILD }
            }
            steps {
                script {
                    env.STRZ_RESOURCES = "${env.WORKSPACE}/strimzi/install/cluster-operator"
                }
                copyArtifacts projectName: 'ocp-downstream-strimzi-prepare-job', filter: 'amq-streams-install-examples.zip', selector: lastSuccessful()
                unzip zipFile: 'amq-streams-install-examples.zip', dir: 'strimzi'
            }
        }



        stage('Configure') {
            steps {
                script {
                    env.OCP_PROJECT_DEBEZIUM = "debezium-${BUILD_NUMBER}"
                    env.OCP_PROJECT_MYSQL = "debezium-${BUILD_NUMBER}-mysql"
                    env.OCP_PROJECT_POSTGRESQL = "debezium-${BUILD_NUMBER}-postgresql"
                    env.OCP_PROJECT_SQLSERVER = "debezium-${BUILD_NUMBER}-sqlserver"
                    env.OCP_PROJECT_MONGO = "debezium-${BUILD_NUMBER}-mongo"
                    env.OCP_PROJECT_DB2 = "debezium-${BUILD_NUMBER}-db2"
                    env.TEST_PROPERTY_VERSION_KAFKA = env.TEST_VERSION_KAFKA ? "-Dversion.kafka=${env.TEST_VERSION_KAFKA}" : ""
                    env.TEST_PROPERTY_TAGS = env.TEST_TAGS ? "-Dgroups=${env.TEST_TAGS}" : ""
                    env.TEST_PROPERTY_TAGS_EXLUDE = env.TEST_TAGS_EXCLUDE ? "-DexcludeGroups=${env.TEST_TAGS_EXCLUDE }" : ""
                }
                withCredentials([
                        usernamePassword(credentialsId: "${OCP_CREDENTIALS}", usernameVariable: 'OCP_USERNAME', passwordVariable: 'OCP_PASSWORD'),
                        usernamePassword(credentialsId: "${QUAY_CREDENTIALS}", usernameVariable: 'QUAY_USERNAME', passwordVariable: 'QUAY_PASSWORD'),

                ]) {
                    sh '''
                    set -x            
                    oc login ${OCP_URL} -u "${OCP_USERNAME}" --password="${OCP_PASSWORD}" --insecure-skip-tls-verify=true >/dev/null
                    oc new-project ${OCP_PROJECT_DEBEZIUM}
                    oc new-project ${OCP_PROJECT_MYSQL}
                    oc new-project ${OCP_PROJECT_POSTGRESQL}
                    oc new-project ${OCP_PROJECT_SQLSERVER}
                    oc new-project ${OCP_PROJECT_MONGO}
                    oc new-project ${OCP_PROJECT_DB2}
                    '''
                    sh '''
                    set -x
                    sed -i "s/namespace: .*/namespace: ${OCP_PROJECT_DEBEZIUM}/" strimzi/install/cluster-operator/*RoleBinding*.yaml
                    oc delete -f ${STRZ_RESOURCES} -n ${OCP_PROJECT_DEBEZIUM} --ignore-not-found
                    oc create -f ${STRZ_RESOURCES} -n ${OCP_PROJECT_DEBEZIUM}
                    '''
                    sh '''
                    set -x
                    oc project ${OCP_PROJECT_SQLSERVER}
                    oc adm policy add-scc-to-user anyuid system:serviceaccount:${OCP_PROJECT_SQLSERVER}:default
                    oc project ${OCP_PROJECT_MONGO}
                    oc adm policy add-scc-to-user anyuid system:serviceaccount:${OCP_PROJECT_MONGO}:default
                    oc project ${OCP_PROJECT_DB2}
                    oc adm policy add-scc-to-user anyuid system:serviceaccount:${OCP_PROJECT_DB2}:default
                    oc adm policy add-scc-to-user privileged system:serviceaccount:${OCP_PROJECT_DB2}:default
                    '''
                    sh '''
                    set -x
                    docker login -u=${QUAY_USERNAME} -p=${QUAY_PASSWORD} quay.io
                    '''
                }
            }
        }

        stage('Build') {
            steps {
                sh '''
                set -x
                cd ${WORKSPACE}/debezium
                mvn clean install -DskipTests -DskipITs -Passembly
                '''
            }
        }

        stage('Build & Deploy Image -- Community') {
            when {
                expression { !params.DBZ_CONNECT_IMAGE && !params.PRODUCT_BUILD }
            }
            steps {
                script {
                    env.DBZ_CONNECT_IMAGE = "quay.io/debezium/testing-system-connect:ci-${currentBuild.number}"
                }
                withCredentials([
                        usernamePassword(credentialsId: "${QUAY_CREDENTIALS}", usernameVariable: 'QUAY_USERNAME', passwordVariable: 'QUAY_PASSWORD'),
                ]) {
                    sh '''
                    set -x 
                    cd ${WORKSPACE}/debezium
                    docker login -u=${QUAY_USERNAME} -p=${QUAY_PASSWORD} quay.io
                    mvn install -pl debezium-testing/debezium-testing-system -DskipTests -DskipITs -Pimage -Dimage.push.skip=false -Dimage.name=${DBZ_CONNECT_IMAGE}   
                    '''
                }
            }
        }

        stage('Test') {
            steps {
                withCredentials([
                        file(credentialsId: "${PULL_SECRET}", variable: 'SECRET_PATH'),
                ]) {
                    sh '''
                    set -x
                    cd ${WORKSPACE}/debezium
                    mvn install -pl debezium-testing/debezium-testing-system -PsystemITs \\
                    -Dimage.fullname="${DBZ_CONNECT_IMAGE}" \\
                    -Dtest.ocp.username="${OCP_USERNAME}" \\
                    -Dtest.ocp.password="${OCP_PASSWORD}" \\
                    -Dtest.ocp.url="${OCP_URL}" \\
                    -Dtest.ocp.project.debezium="${OCP_PROJECT_DEBEZIUM}" \\
                    -Dtest.ocp.project.mysql="${OCP_PROJECT_MYSQL}"  \\
                    -Dtest.ocp.project.postgresql="${OCP_PROJECT_POSTGRESQL}" \\
                    -Dtest.ocp.project.sqlserver="${OCP_PROJECT_SQLSERVER}"  \\
                    -Dtest.ocp.project.mongo="${OCP_PROJECT_MONGO}" \\
                    -Dtest.ocp.project.db2="${OCP_PROJECT_DB2}" \\
                    -Dtest.ocp.pull.secret.paths="${SECRET_PATH}" \\
                    -Dtest.wait.scale="${TEST_WAIT_SCALE}" \\
                    ${TEST_PROPERTY_VERSION_KAFKA} \\
                    ${TEST_PROPERTY_TAGS} \\
                    ${TEST_PROPERTY_TAGS_EXCLUDE}
                    '''
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts '**/target/failsafe-reports/*.xml'
            junit '**/target/failsafe-reports/*.xml'

            mail to: 'jcechace@redhat.com', subject: "Debezium OpenShift test run #${BUILD_NUMBER} finished", body: """
OpenShift interoperability test run ${BUILD_URL} finished with result: ${currentBuild.currentResult}
"""
        }
        success {
            sh '''
            oc delete project ${OCP_PROJECT_DEBEZIUM}
            oc delete project ${OCP_PROJECT_MYSQL}
            oc delete project ${OCP_PROJECT_POSTGRESQL}
            oc delete project ${OCP_PROJECT_SQLSERVER}
            oc delete project ${OCP_PROJECT_MONGO}
            oc delete project ${OCP_PROJECT_DB2}
            '''
        }
    }
}
