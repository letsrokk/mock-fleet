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

former_module = ["fleet", "wiremock", "updater"].join("-")
former_component = ["wiremock", "updater"].join("-")
former_helm_key = ["wiremock", "versionUpdater"].join(".")
former_role = ["up", "dater"].join
active_paths = [
  ".github/workflows",
  "README.md",
  "bin",
  "deploy/helm/mock-fleet",
  "docs/exploratory-checklist.md",
  "fleet-mock-ops",
  "tests"
]
forbidden = [former_module, former_component, former_helm_key,
             "com.github.letsrokk.updater", "UpdaterCommand", "UpdaterConfig", "UPDATER_", former_role]
legacy_key_rejections = {
  "deploy/helm/mock-fleet/values.schema.json" => [
    '"not": { "required": ["versionUpdater"] }'
  ],
  "deploy/helm/mock-fleet/tests/render-mock-ops.sh" => [
    '--set wiremock.versionUpdater.enabled=true',
    'Chart accepted removed wiremock.versionUpdater values'
  ]
}
active_paths.each do |path|
  files = File.directory?(path) ? Dir.glob("#{path}/**/*", File::FNM_DOTMATCH) : [path]
  files.select { |file| File.file?(file) && file != __FILE__ }.each do |file|
    next if file.include?("/target/")
    content = File.binread(file)
    next if content.include?("\x00")
    legacy_key_rejections.fetch(file, []).each do |fragment|
      require_contract(content.scan(fragment).length == 1,
                       "#{file} must contain exactly one clean-break rejection fragment: #{fragment}")
      content = content.sub(fragment, "")
    end
    forbidden.each do |term|
      require_contract(!content.downcase.include?(term.downcase), "#{file} still contains #{term}")
    end
  end
end

puts "Fleet Mock Ops module contract passed."
