/**
 * Define utility functions.
 */

def appNameFromDemoBranch(branchname){
    return branchname[5..-1].replaceAll('_', '-')
}

def demoAppName(branchname) {
    def appname = appNameFromDemoBranch(branchname)
    return "bedrock-demo-${appname}"
}

def demoDeployYaml(branchname) {
    def appname = appNameFromDemoBranch(branchname)
    return "${appname}-deploy.yaml"
}

def demoAppURL(appname, region) {
    if ( appname ==~ /^bedrock-demo-[1-5]$/ ) {
        return "https://www-demo${appname[-1]}.allizom.org"
    }
    return "https://${appname}.${region.name}.moz.works"
}

/**
 * Send a notice to #www-notify on irc.mozilla.org with the build result
 *
 * @param stage step of build/deploy
 * @param result outcome of build (will be uppercased)
*/
def ircNotification(Map args) {
    def command = "bin/irc-notify.sh"
    for (arg in args) {
        command += " --${arg.key} '${arg.value}'"
    }
    sh command
}

def pushDockerhub(from_repo) {
    withCredentials([[$class: 'StringBinding',
                      credentialsId: 'DOCKER_PASSWORD',
                      variable: 'DOCKER_PASSWORD']]) {
        withEnv(['DOCKER_USERNAME=mozjenkins',
                 "FROM_DOCKER_REPOSITORY=${from_repo}"]) {
            retry(2) {
                sh 'docker/bin/push2dockerhub.sh'
            }
        }
    }
}

def integrationTestJob(propFileName, appURL='') {
    return {
        node {
            unstash 'workspace'
            def testScript = "docker/bin/run_integration_tests.sh ${propFileName}".toString()
            withCredentials([[$class: 'UsernamePasswordMultiBinding',
                              credentialsId: 'SAUCELABS_CREDENTIALS',
                              usernameVariable: 'SAUCELABS_USERNAME',
                              passwordVariable: 'SAUCELABS_API_KEY']]) {
                withEnv(["BASE_URL=${appURL}"]) {
                    retry(2) {
                        try {
                            sh testScript
                        }
                        finally {
                            junit 'results/*.xml'
                            if ( propFileName == 'smoke' ) {
                                sh 'docker/bin/cleanup_after_functional_tests.sh'
                            }
                        }
                    }
                }
            }
        }
    }
}

def deploymentJob(config, region, appname, tested_apps) {
    return {
        node {
            if ( config.demo ) {
                appURL = utils.demoAppURL(appname, region)
                namespace = 'bedrock-demo'
            } else {
                appURL = "https://${appname}.${region.name}.moz.works"
                namespace = appname
            }
            stageName = "Deploy ${appname}-${region.name}"
            // ensure no deploy/test cycle happens in parallel for an app/region
            lock (stageName) {
                stage (stageName) {
                    if ( region.deis_bin ) {
                        utils.pushDeis(region, config, appname, stageName)
                    } else if (region.config_repo){
                        utils.deploy(region, config, appname, stageName, namespace)
                    }
                    utils.ircNotification([message: appURL, status: 'shipped'])
                }
                if ( config.integration_tests ) {
                    // queue up test closures
                    def allTests = [:]
                    def regionTests = config.integration_tests[regionId]
                    for (filename in regionTests) {
                        allTests[filename] = utils.integrationTestJob(filename, appURL)
                    }
                    stage ("Test ${appname}-${region.name}") {
                        try {
                            // wait for server to be ready
                            sleep(time: 10, unit: 'SECONDS')
                            if ( allTests.size() == 1 ) {
                                allTests[regionTests[0]]()
                            } else {
                                parallel allTests
                            }
                        } catch(err) {
                            utils.ircNotification([stage: "Integration Tests ${appname}-${region.name}", status: 'failure'])
                            throw err
                        }
                        tested_apps << "${appname}-${region.name}".toString()
                    }
                }
            }
        }
    }
}

def pushDeis(region, config, appname, stageName) {
    withEnv(["DEIS_PROFILE=${region.name}",
            "DEIS_BIN=${region.deis_bin}",
            "DEIS_APPLICATION=${appname}"]) {
        try {
            retry(3) {
                if (config.demo) {
                    withCredentials([[$class: 'StringBinding',
                                      credentialsId: 'SENTRY_DEMO_DSN',
                                      variable: 'SENTRY_DEMO_DSN']]) {
                        sh 'docker/bin/prep_demo.sh'
                    }
                }
                sh 'docker/bin/push2deis.sh'
            }
        } catch(err) {
            ircNotification([stage: stageName, status: 'failure'])
            throw err
        }
    }
}

def deploy(region, config, appname, stageName, namespace) {

    def deployYaml = ""
    if (config.demo) {
        deployYaml = demoDeployYaml(env.BRANCH_NAME)
    } else {
        deployYaml = "deploy.yaml"
    }

    withEnv(["CLUSTER_NAME=${region.get('cluster_name', region.name)}",
             "CONFIG_REPO=${region.config_repo}",
             "CONFIG_BRANCH=${region.config_branch}",
             "NAMESPACE=${namespace}",
             "DEPLOYMENT_LOG_BASE_URL=${region.deployment_log_base_url}",
             "DEPLOYMENT_YAML=${deployYaml}"]) {
        try {
            sh 'bin/deploy.sh'
        } catch(err) {
            ircNotification([stage: stageName, status: 'failure'])
            throw err
        }
    }
}

return this;
