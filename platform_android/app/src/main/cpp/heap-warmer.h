// heap-warmer.h: Background thread that pages swapped-out malloc heap (SoundFont samples) back into RAM.
// NOT concerned with: which allocations hold samples, the audio callback, or locking memory (mlock).
#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/uio.h>
#include <unistd.h>

// Android compresses an idle app's memory into swap (zram), and an app with the
// screen off is idle. SoundFont samples then stall the audio callback on the first
// notes after a pause while their pages decompress (a 12 ms callback for 8 voices).
// mlock can't help: apps may lock only 64 MB, and a piano SoundFont is several
// hundred. Instead, every few seconds this thread finds heap pages that are no
// longer resident and reads them back, so that cost lands here, off the RT thread.
//
// It walks every large readable malloc region (jemalloc's "[anon:libc_malloc]" or
// Scudo's "[anon:scudo:*]"), which is where FluidSynth's sample data lives.
// Pages are read with process_vm_readv on our own pid, which reports a range
// that was unmapped in the meantime as an error instead of crashing.
class HeapWarmer {
public:
    ~HeapWarmer() { stop(); }

    void start() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (thread_.joinable()) return;
        stopping_ = false;
        thread_ = std::thread([this] { run(); });
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
        }
        cv_.notify_all();
        if (thread_.joinable()) thread_.join();
    }

    // Non-resident pages read since start (cumulative). Includes never-touched and
    // allocator-released pages (they map the shared zero page), so it overstates swap-ins.
    int pagesRestored() const { return restored_.load(std::memory_order_relaxed); }

private:
    static constexpr auto kInterval = std::chrono::seconds(2);
    static constexpr size_t kMinRegionBytes = 1 << 20;
    static constexpr size_t kBatch = 512;  // remote iovecs per process_vm_readv (<= IOV_MAX)

    void run() {
        setpriority(PRIO_PROCESS, 0, 10);  // this thread only; stays clear of the UI and audio
        long page = sysconf(_SC_PAGESIZE);
        std::unique_lock<std::mutex> lock(mutex_);
        while (!stopping_) {
            lock.unlock();
            int restored = pass(static_cast<size_t>(page));
            if (restored > 0) {
                restored_.fetch_add(restored, std::memory_order_relaxed);
            }
            lock.lock();
            cv_.wait_for(lock, kInterval, [this] { return stopping_; });
        }
    }

    // Returns the number of non-resident pages read back.
    int pass(size_t page) {
        FILE* maps = fopen("/proc/self/maps", "re");
        if (!maps) return 0;
        regions_.clear();
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (!strstr(line, "[anon:libc_malloc]") && !strstr(line, "[anon:scudo:")) continue;
            unsigned long start, end;
            char perms[5];
            if (sscanf(line, "%lx-%lx %4s", &start, &end, perms) != 3) continue;
            if (perms[0] != 'r' || end - start < kMinRegionBytes) continue;
            regions_.push_back({start, end});
        }
        fclose(maps);

        int restored = 0;
        pid_t pid = getpid();
        for (const auto& r : regions_) {
            size_t pages = (r.end - r.start) / page;
            residency_.resize(pages);
            if (mincore(reinterpret_cast<void*>(r.start), r.end - r.start, residency_.data()) != 0) {
                continue;  // unmapped since we read maps
            }
            remote_.clear();
            for (size_t i = 0; i < pages; i++) {
                if (residency_[i] & 1) continue;
                remote_.push_back({reinterpret_cast<void*>(r.start + i * page), 1});
                if (remote_.size() == kBatch) restored += readPages(pid);
            }
            restored += readPages(pid);
        }
        return restored;
    }

    int readPages(pid_t pid) {
        if (remote_.empty()) return 0;
        char sink[kBatch];
        iovec local{sink, remote_.size()};
        ssize_t n = process_vm_readv(pid, &local, 1, remote_.data(), remote_.size(), 0);
        remote_.clear();
        return n > 0 ? static_cast<int>(n) : 0;
    }

    struct Region { unsigned long start, end; };

    std::thread thread_;
    std::mutex mutex_;
    std::condition_variable cv_;
    bool stopping_ = false;
    std::atomic<int> restored_{0};
    // Reused across passes; touched only by the warmer thread.
    std::vector<Region> regions_;
    std::vector<unsigned char> residency_;
    std::vector<iovec> remote_;
};
