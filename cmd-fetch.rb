#!/usr/bin/env ruby

RUNS = 100
HOME = Dir.pwd
#REPOS = [ ".", "community", "contrib", "CIDR", "community/android", "community/android/tools-base" ]
REPOS = `find . -type d -name '.git' -maxdepth 6`.gsub("/.git", "").gsub("./", "").split

def fetch(relpath)
  Dir.chdir(File.join(HOME, relpath))
  start = Time.now
  `git fetch`
  endd = Time.now
  return endd - start
end

#cold fetch
puts "Cold fetching..."
REPOS.each do |repo|
  path = repo.empty? ? HOME : repo
  fetch(repo)
end

results = Hash.new

RUNS.times do |step|
  REPOS.each do |repo|
    path = repo == "." ? HOME : repo
    print "Fetching ##{step+1} in #{path}..."
    time = (fetch(repo) * 1000.0).to_i 
    puts " took #{time} ms" 
    
    if (!results[path]) then results[path] = [time]
    else results[path] << time
    end
  end
end

calculated = Hash.new
results.each do |path, results|
  size = results.size
  perc10 = [2, size / 10].max
  filtered = results[perc10..-perc10-1]
  calculated[path] = filtered.inject{|sum, x| sum + x } / filtered.size
  puts "#{path}: #{calculated[path]} ms"
end
