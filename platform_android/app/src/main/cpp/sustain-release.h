// Concern: guarantees ordered sustain release under queue saturation | Non-concern: rendering | IO: (deferred channel releases) -> ordered controls
#pragma once

#include <atomic>
#include <cstdint>
#include <cstddef>

constexpr bool hasSynthEventQueueRoom(std::size_t available, std::size_t reserved, bool essential, bool pedalOff) {
    return (essential && !pedalOff) || available > reserved;
}

class OrderedSustainRelease {
    static_assert(std::atomic<uint32_t>::is_always_lock_free, "Sustain recovery must not lock on the audio callback");
    struct Channel {
        uint64_t orders[2]{};
        std::atomic<uint32_t> read{0};
        std::atomic<uint32_t> write{0};

        bool room() const { return write.load(std::memory_order_relaxed) - read.load(std::memory_order_acquire) < 2; }
        void push(uint64_t order) {
            const auto index = write.load(std::memory_order_relaxed);
            if (index - read.load(std::memory_order_acquire) >= 2) return;
            orders[index % 2] = order;
            write.store(index + 1, std::memory_order_release);
        }
        uint64_t front() const {
            const auto index = read.load(std::memory_order_relaxed);
            return index == write.load(std::memory_order_acquire) ? 0 : orders[index % 2];
        }
        void pop() { read.store(read.load(std::memory_order_relaxed) + 1, std::memory_order_release); }
    };
public:
    bool canAcceptPedalOn(int channel) const { return channel >= 0 && channel < 16 && pending_[channel].room(); }
    void defer(int channel, uint64_t order) {
        if (channel >= 0 && channel < 16) pending_[channel].push(order);
    }

    uint64_t latest() const {
        uint64_t result = 0;
        for (const auto& value : pending_) {
            const auto order = value.front();
            if (order > result) result = order;
        }
        return result;
    }

    bool acceptPedal(int channel, uint64_t order) {
        if (channel < 0 || channel >= 16) return false;
        if (order < applied_[channel]) return false;
        applied_[channel] = order;
        return true;
    }

    template <typename Release>
    void drainBefore(uint64_t limit, Release release) {
        while (true) {
            int selected = -1;
            uint64_t earliest = limit;
            for (int channel = 0; channel < 16; ++channel) {
                const auto order = pending_[channel].front();
                if (order != 0 && order <= limit && (selected < 0 || order < earliest)) {
                    selected = channel;
                    earliest = order;
                }
            }
            if (selected < 0) break;
            pending_[selected].pop();
            if (acceptPedal(selected, earliest)) release(selected);
        }
    }

private:
    Channel pending_[16]{};
    uint64_t applied_[16]{};
};

template <typename Event, typename Queue, typename Handle, typename Release>
void drainSustainOrdered(Queue& queue, OrderedSustainRelease& pending, Handle handle, Release release) {
    Event event;
    bool buffered = false;
    while (true) {
        if (!buffered && queue.read(&event, 1) != 1) {
            const auto limit = pending.latest();
            if (limit == 0) break;
            if (queue.read(&event, 1) == 1) { buffered = true; continue; }
            pending.drainBefore(limit, release);
            continue;
        }
        buffered = false;
        pending.drainBefore(event.order, release);
        handle(event);
    }
}
