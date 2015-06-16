/*
 * Copyright 2005-2014 Red Hat, Inc.                                    
 *                                                                      
 * Red Hat licenses this file to you under the Apache License, version  
 * 2.0 (the "License"); you may not use this file except in compliance  
 * with the License.  You may obtain a copy of the License at           
 *                                                                      
 *    http://www.apache.org/licenses/LICENSE-2.0                        
 *                                                                      
 * Unless required by applicable law or agreed to in writing, software  
 * distributed under the License is distributed on an "AS IS" BASIS,    
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or      
 * implied.  See the License for the specific language governing        
 * permissions and limitations under the License.
 */
package io.fabric8.maven;

import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.api.KubernetesClient;
import io.fabric8.kubernetes.api.ServiceNames;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.taiga.ModuleDTO;
import io.fabric8.taiga.ProjectDTO;
import io.fabric8.taiga.TaigaClient;
import io.fabric8.taiga.TaigaKubernetes;
import io.fabric8.taiga.TaigaModule;
import io.fabric8.utils.Strings;
import io.fabric8.utils.URLUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates an OpenShift BuildConfig object for a
 */
@Mojo(name = "create-build-config", requiresProject = false)
public class CreateBuildConfigMojo extends AbstractNamespacedMojo {

    /**
     * the gogs user name to use
     */
    @Parameter(property = "fabric8.gogsUsername")
    protected String username;

    /**
     * the gogs password
     */
    @Parameter(property = "fabric8.gogsPassword")
    protected String password;

    /**
     *
     */
    @Parameter(property = "fabric8.repoName")
    protected String repoName;

    /**
     *
     */
    @Parameter(property = "fabric8.fullName")
    protected String fullName;

    /**
     *
     */
    @Parameter(property = "fabric8.gitUrl")
    protected String gitUrl;

    /**
     * The webhook secret used for generic and github webhooks
     */
    @Parameter(property = "fabric8.webhookSecret", defaultValue = "secret101")
    protected  String secret;

    /**
     * the build image stream name
     */
    @Parameter(property = "fabric8.buildImageStream", defaultValue = "triggerJenkins")
    protected  String buildImageStream;

    /**
     * the build image stream tag
     */
    @Parameter(property = "fabric8.buildImageTag", defaultValue = "latest")
    protected  String buildImageTag;

    /**
     * The name of the jenkins job to link to as the first job in the pipeline
     */
    @Parameter(property = "fabric8.jenkinsJob")
    protected  String jenkinsJob;

    /**
     * The name of the jenkins monitor view
     */
    @Parameter(property = "fabric8.jenkinsMonitorView")
    protected  String jenkinsMonitorView;

    /**
     * The name of the jenkins pipline view
     */
    @Parameter(property = "fabric8.jenkinsPipelineView")
    protected  String jenkinsPipelineView;

    /**
     * The name of the taiga project name to use
     */
    @Parameter(property = "fabric8.tagiaProjectName", defaultValue = "${fabric8.repoName}")
    protected String taigaProjectName;

    /**
     * The slug name of the project in Taiga or will be auto-generated from the user and project name if not configured
     */
    @Parameter(property = "fabric8.taigaProjectSlug")
    protected String taigaProjectSlug;

    /**
     * The user name to use for taiga links
     */
    @Parameter(property = "fabric8.tagiaUserName")
    protected String taigaUserName;

    /**
     * The kind of taiga project to link to
     */
    @Parameter(property = "fabric8.taigaProjectKind", defaultValue = "kanban")
    protected String taigaProjectKind;

    /**
     * Should we auto-create projects in taiga if they are missing?
     */
    @Parameter(property = "fabric8.taigaAutoCreate", defaultValue = "true")
    protected boolean taigaAutoCreate;

    /**
     * Should we enable Taiga integration
     */
    @Parameter(property = "fabric8.taigaEnabled", defaultValue = "true")
    protected boolean taigaEnabled;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        KubernetesClient kubernetes = getKubernetes();

        String name = repoName;
        if (Strings.isNotBlank(username)) {
            name = username + "-" + name;
        }
        Map<String,String> labels = new HashMap<>();
        labels.put("user", username);
        labels.put("repo", repoName);

        TaigaClient taiga = createTaiga();

        Map<String,String> annotations = new HashMap<>();
        String jenkinsJobUrl = null;
        try {
            String jenkinsUrl = kubernetes.getServiceURL(ServiceNames.JENKINS, kubernetes.getNamespace(), "http", true);

            if (Strings.isNotBlank(jenkinsUrl)) {
                if (Strings.isNotBlank(jenkinsMonitorView)) {
                    annotations.put("fabric8.link.jenkins.monitor/url", URLUtils.pathJoin(jenkinsUrl, "/view", jenkinsMonitorView));
                    annotations.put("fabric8.link.jenkins.monitor/label", "Monitor");
                }
                if (Strings.isNotBlank(jenkinsPipelineView)) {
                    annotations.put("fabric8.link.jenkins.pipeline/url", URLUtils.pathJoin(jenkinsUrl, "/view", jenkinsPipelineView));
                    annotations.put("fabric8.link.jenkins.pipeline/label", "Pipeline");
                }
                if (Strings.isNotBlank(jenkinsJob)) {
                    jenkinsJobUrl = URLUtils.pathJoin(jenkinsUrl, "/job", jenkinsJob);
                    annotations.put("fabric8.link.jenkins.job/url", jenkinsJobUrl);
                    annotations.put("fabric8.link.jenkins.job/label", "Job");
                }
            }
        } catch (Exception e) {
            getLog().warn("Could not find the Jenkins URL!: " + e, e);
        }

        String taigaLink = getTaigaProjectUrl(taiga);
        if (Strings.isNotBlank(taigaLink)) {
            annotations.put("fabric8.link.taiga/url", taigaLink);
            annotations.put("fabric8.link.taiga/label", "Issues");
        }

        BuildConfig buildConfig = new BuildConfigBuilder().
                withNewMetadata().withName(name).withLabels(labels).withAnnotations(annotations).endMetadata().
                withNewSpec().
                withNewSource().
                withType("Git").withNewGit().withUri(gitUrl).endGit().
                endSource().
                withNewStrategy().
                withType("Docker").withNewDockerStrategy().withNewFrom().withName(buildImageStream + ":" + buildImageTag).endFrom().endDockerStrategy().
                endStrategy().
                addNewTrigger().
                withType("github").withNewGithub().withSecret(secret).endGithub().
                endTrigger().
                addNewTrigger().
                withType("generic").withNewGeneric().withSecret(secret).endGeneric().
                endTrigger().
                endSpec().
                build();

        Controller controller = createController();
        controller.applyBuildConfig(buildConfig, "maven");
        getLog().info("Created build configuration for " + name + " in namespace: " + controller.getNamespace() + " at " + kubernetes.getAddress());

        createJenkinsWebhook(jenkinsJobUrl);
        createTaigaWebhook(taiga);
    }

    protected TaigaClient createTaiga() {
        if (!taigaEnabled) {
            return null;
        }
        TaigaClient taiga = TaigaKubernetes.createTaiga(getKubernetes());
        taiga.setAutoCreateProjects(taigaAutoCreate);
        return taiga;
    }

    protected String getTaigaProjectUrl(TaigaClient taiga) {
        String url = taiga.getAddress();
        if (Strings.isNotBlank(url) && Strings.isNotBlank(taigaProjectName) && Strings.isNotBlank(taigaUserName)) {
            return URLUtils.pathJoin(url, "/project/", taigaUserName + "-" + taigaProjectName + "/", taigaProjectKind);
        }
        return null;
    }

    protected void createJenkinsWebhook(String jenkinsJobUrl) {
        if (Strings.isNotBlank(jenkinsJobUrl)) {
            String jenkinsWebHook = URLUtils.pathJoin(jenkinsJobUrl, "/build");
            createWebhook(jenkinsWebHook, this.secret);
        }
    }

    protected void createTaigaWebhook(TaigaClient taiga) {
        if (taiga != null) {
            ProjectDTO project = taiga.getOrCreateProject(taigaProjectName, taigaProjectSlug);
            if (project != null) {
                Long projectId = project.getId();
                ModuleDTO module = taiga.moduleForProject(projectId, TaigaModule.GOGS);
                if (module != null) {
                    String webhookSecret = module.getSecret();
                    String webhook = taiga.getPublicWebhookUrl(module);
                    if (Strings.isNotBlank(webhookSecret) && Strings.isNotBlank(webhook)) {
                        createWebhook(webhook, webhookSecret);
                    } else {
                        getLog().warn("Could not create webhook for Taiga. Missing module data for url: " + webhook + " secret: " + webhookSecret);
                    }
                } else {
                    getLog().warn("No module for gogs so cannot create Taiga webhook");
                }
            }
        }
    }

    protected void createWebhook(String url, String webhookSecret) {
        try {
            CreateGogsWebhook.createGogsWebhook(getKubernetes(), getLog(), username, password, repoName, url, webhookSecret);
        } catch (Exception e) {
            getLog().error("Failed to create webhook " + url + " on repository " + repoName + ". Reason: " + e, e);
        }
    }
}
