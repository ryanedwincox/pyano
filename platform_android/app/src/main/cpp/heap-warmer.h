// Concern: restores idle heap pages to RAM | Non-concern: audio callback work | IO: (memory maps) -> resident pages
#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <pthread.h>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/uio.h>
#include <unistd.h>

// Restore nonresident sample pages off the RT thread; process_vm_readv safely tolerates concurrent unmaps. See docs/native-audio.md.
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

    // Check less often while idle; run immediately on wake to restore pages before playing.
    void setIdle(bool idle) {
        bool was = idle_.exchange(idle);
        if (was && !idle) cv_.notify_all();
    }

    // Cumulative restored pages include untouched/released zero pages, so this overstates actual swap-ins.
    int pagesRestored() const { return restored_.load(std::memory_order_relaxed); }

private:
    static constexpr auto kInterval = std::chrono::seconds(2);
    static constexpr auto kIdleInterval = std::chrono::seconds(30);
    static constexpr size_t kMinRegionBytes = 1 << 20;
    static constexpr size_t kBatch = 512;  // remote iovecs per process_vm_readv (<= IOV_MAX)

    void run() {
        pthread_setname_np(pthread_self(), "HeapWarmer");
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
            bool wasIdle = idle_.load();
            cv_.wait_for(lock, wasIdle ? kIdleInterval : kInterval,
                         [this, wasIdle] { return stopping_ || (wasIdle && !idle_.load()); });
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
    std::atomic<bool> idle_{false};
    // Reused across passes; touched only by the warmer thread.
    std::vector<Region> regions_;
    std::vector<unsigned char> residency_;
    std::vector<iovec> remote_;
};
