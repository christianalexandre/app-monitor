#!/usr/bin/env bash
# Usage: ruleset_check.sh [repo-dir]
# Each required check of .github/rulesets/protect-{dev,main}.json must be a job name that a workflow
# running on pull requests to that branch reports, and every release gate job must be required on
# main (DEBT-0048). Matrix jobs are named as GitHub names them: `job (value, value…)`.
set -euo pipefail
cd "${1:-$(dirname "$0")/..}"

command -v ruby >/dev/null 2>&1 || { echo "ruleset-check: SKIPPED (no ruby)"; exit 0; }

ruby -ryaml -rjson - <<'RUBY'
# Names a workflow's jobs report, for pull requests to `branch`.
def reported(branch)
  Dir[".github/workflows/*.yml"].sort.flat_map do |file|
    wf = YAML.load_file(file)
    on = wf["on"] || wf[true]
    pr = on.is_a?(Hash) ? on["pull_request"] : nil
    next [] unless on.is_a?(Hash) && on.key?("pull_request")
    branches = pr.is_a?(Hash) ? pr["branches"] : nil
    next [] if branches && !branches.include?(branch)
    (wf["jobs"] || {}).flat_map do |key, job|
      name = job["name"] || key
      matrix = job.dig("strategy", "matrix")
      next [[file, name]] unless matrix.is_a?(Hash)
      rows = if matrix["include"] && matrix.keys == ["include"]
        matrix["include"]
      else
        axes = matrix.reject { |k, _| %w[include exclude].include?(k) }
        axes.values.first.product(*axes.values.drop(1)).map { |vs| axes.keys.zip(vs).to_h }
      end
      rows.map { |row| [file, "#{name} (#{row.values.join(", ")})"] }
    end
  end
end

failures = []
{ "dev" => "protect-dev.json", "main" => "protect-main.json" }.each do |branch, ruleset|
  rules = JSON.parse(File.read(".github/rulesets/#{ruleset}"))["rules"]
  required = rules.select { |r| r["type"] == "required_status_checks" }
    .flat_map { |r| r["parameters"]["required_status_checks"].map { |c| c["context"] } }
  jobs = reported(branch)
  names = jobs.map(&:last)
  (required - names).each { |c| failures << "#{ruleset}: '#{c}' is no job name on pull requests to #{branch}" }
  if branch == "main"
    jobs.select { |f, _| f.end_with?("release-gate.yml") }.map(&:last).each do |n|
      failures << "#{ruleset}: release gate job '#{n}' is not a required check" unless required.include?(n)
    end
  end
end

if failures.empty?
  puts "ruleset-check: ok"
else
  failures.each { |f| warn "ruleset-check: #{f}" }
  exit 1
end
RUBY
