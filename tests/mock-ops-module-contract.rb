#!/usr/bin/env ruby
# frozen_string_literal: true

def require_contract(condition, message)
  abort(message) unless condition
end

module_root = "fleet-mock-ops"
required = [
  "#{module_root}/pom.xml",
  "#{module_root}/mvnw",
  "#{module_root}/mvnw.cmd",
  "#{module_root}/.mvn/wrapper/MavenWrapperDownloader.java",
  "#{module_root}/.mvn/wrapper/maven-wrapper.properties",
  "#{module_root}/src/main/java/com/github/letsrokk/mockops/MockOpsCommand.java",
  "#{module_root}/src/main/java/com/github/letsrokk/mockops/MockOpsConfig.java",
  "#{module_root}/src/test/java/com/github/letsrokk/mockops/MockOpsCommandTest.java"
]
required.each { |path| require_contract(File.file?(path), "Missing #{path}") }
require_contract(!Dir.exist?("fleet-wiremock-updater"), "Former module directory still exists")

wrapper = File.read("#{module_root}/mvnw")
require_contract(!wrapper.include?("../fleet-api/mvnw"), "Mock Ops wrapper delegates to Fleet API")
properties = File.read("#{module_root}/.mvn/wrapper/maven-wrapper.properties")
require_contract(properties.include?("wrapperVersion=3.3.2"), "Wrong Maven Wrapper version")
require_contract(properties.include?("apache-maven-3.9.9-bin.zip"), "Wrong Maven distribution")

pom = File.read("#{module_root}/pom.xml")
require_contract(pom.include?("<artifactId>fleet-mock-ops</artifactId>"), "Wrong Maven artifact")
puts "Fleet Mock Ops module contract passed."
