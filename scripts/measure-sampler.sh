#!/usr/bin/env bash
# Samples emulator/container RSS, memory, swap, load and CPU idle every ~5 s into outfile until killed. Usage: measure-sampler.sh outfile
out=$1
echo "ts rss_emu_mb rss_redroid_mb rss_java_mb used_mb swap_mb avail_mb load1 cpu_idle" > "$out"
while :; do
  emu=$(ps -eo rss,args | awk '/qemu-system-x86_64-headless/ && !/awk/ {s+=$1} END{printf "%d", s/1024}')
  red=$(docker stats --no-stream --format "{{.MemUsage}}" 2>/dev/null | awk "{v=\$1; if (v ~ /GiB/) {sub(/GiB/,\"\",v); s+=v*1024} else {sub(/MiB/,\"\",v); s+=v}} END{printf \"%d\", s}")
  jv=$(ps -eo rss,args | awk '/[G]radleDaemon|[k]otlin/ {s+=$1} END{printf "%d", s/1024}')
  used=$(free -m | awk "/Mem/{print \$3}"); avail=$(free -m | awk "/Mem/{print \$7}"); sw=$(free -m | awk "/Swap/{print \$3}")
  idle=$(vmstat 1 2 | tail -1 | awk '{print $15}')
  echo "$(date +%T) $emu $red $jv $used $sw $avail $(cut -d' ' -f1 /proc/loadavg) $idle" >> "$out"
  sleep 4
done
