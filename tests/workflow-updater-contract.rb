#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

def workflow(path)
  YAML.safe_load(File.read(path), aliases: true)
end

def fail_contract(message)
  warn(message)
  exit(1)
end

build = workflow(".github/workflows/build.yml")
helm_commands = build.dig("jobs", "helm", "steps").map { |step| step.fetch("run", "") }.join("\n")
unless helm_commands.include?("deploy/helm/mock-fleet/tests/render-updater.sh") &&
    helm_commands.include?("ruby tests/workflow-updater-contract.rb")
  fail_contract("Build workflow does not run the updater Helm and workflow contracts")
end
updater_job = build.fetch("jobs", {}).fetch("fleet-wiremock-updater", nil)
fail_contract("Build workflow does not package fleet-wiremock-updater") unless updater_job
build_steps = updater_job.fetch("steps", []).map { |step| step.fetch("run", "") }.join("\n")
fail_contract("Updater build does not run its Maven wrapper") unless build_steps.include?("./mvnw -B package")
build_artifacts = updater_job.fetch("steps", []).select { |step| step["uses"] == "actions/upload-artifact@v4" }
unless build_artifacts.any? { |step| step.dig("with", "name") == "fleet-wiremock-updater-package" && step.dig("with", "path") == "fleet-wiremock-updater/target/quarkus-app" }
  fail_contract("Updater package artifact is missing")
end

publish = workflow(".github/workflows/publish.yml")
fail_contract("Publish workflow has no updater image name") unless publish.dig("env", "UPDATER_IMAGE_NAME") == "ghcr.io/letsrokk/mock-fleet/wiremock-updater"
publish_steps = publish.dig("jobs", "publish", "steps") || []
unless publish_steps.any? { |step| step.dig("with", "name") == "fleet-wiremock-updater-package" && step.dig("with", "path") == "fleet-wiremock-updater/target/quarkus-app" }
  fail_contract("Publish workflow does not download the updater package")
end
updater_metadata_step = publish_steps.find { |step| step["name"] == "Extract WireMock updater Docker metadata" }
unless updater_metadata_step&.dig("with", "annotations")&.include?("mock-fleet-wiremock-updater")
  fail_contract("Updater image annotations identify the wrong component")
end
updater_image_step = publish_steps.find { |step| step["name"] == "Build and push WireMock updater Docker image" }
unless updater_image_step&.dig("with", "context") == "fleet-wiremock-updater" &&
    updater_image_step&.dig("with", "file") == "fleet-wiremock-updater/src/main/docker/Dockerfile.jvm" &&
    updater_image_step&.dig("with", "push") == true
  fail_contract("Publish workflow does not push the updater image")
end

cluster = workflow(".github/workflows/cluster-e2e.yml")
cluster_steps = cluster.dig("jobs", "cluster-e2e", "steps") || []
cluster_commands = cluster_steps.map { |step| step.fetch("run", "") }.join("\n")
fail_contract("Cluster workflow does not package the updater") unless cluster_commands.include?("fleet-wiremock-updater/mvnw")
unless cluster_commands.include?("fleet-wiremock-updater/src/main/docker/Dockerfile.jvm") &&
    cluster_commands.include?("mock-fleet/wiremock-updater:${MOCK_FLEET_E2E_IMAGE_TAG}")
  fail_contract("Cluster workflow does not build the updater image inside Minikube")
end

puts "Updater workflow contract passed."
