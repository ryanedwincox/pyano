// Concern: verifies saturated sustain delivery ordering | Non-concern: real synthesis | IO: (full event queue and releases) -> assertions
#include "ring-buffer.h"
#include "sustain-release.h"
#include <cassert>
#include <vector>

struct Event { uint64_t order; int channel; int value; };
struct Fixture {
    SpscRingBuffer<Event> queue;
    std::size_t reserved;
    explicit Fixture(std::size_t capacity = 4, std::size_t reserve = 0) : queue(capacity), reserved(reserve) {}
    OrderedSustainRelease releases;
    std::vector<int> controls;
    uint64_t next = 0;
    bool emit(int value) {
        Event event{++next, 2, value};
        if (value >= 64 && !releases.canAcceptPedalOn(2)) return false;
        if (hasSynthEventQueueRoom(queue.availableWrite(), reserved, value < 64, value < 64) && queue.write(&event, 1) == 1) return true;
        if (value >= 64) return false;
        releases.defer(2, event.order);
        return true;
    }
    void apply(const Event& event) {
        if (releases.acceptPedal(event.channel, event.order)) controls.push_back(event.value);
    }
    void consumeOne() { Event event; assert(queue.read(&event, 1) == 1); apply(event); }
    void drain() {
        drainSustainOrdered<Event>(queue, releases, [this](const Event& event) { apply(event); },
            [this](int channel) { assert(channel == 2); controls.push_back(0); });
    }
};

int main() {
    Fixture baseline;
    for (int i = 0; i < 4; ++i) assert(baseline.emit(127));
    assert(baseline.emit(0));
    baseline.consumeOne();
    assert(baseline.emit(127));
    baseline.drain();
    assert(baseline.controls.size() == 6);
    assert(baseline.controls[4] == 0 && baseline.controls[5] == 127);
    assert(baseline.releases.latest() == 0);
    assert(!baseline.releases.acceptPedal(2, 5));
    assert(baseline.releases.acceptPedal(3, 1));

    Fixture repeated;
    for (int cycle = 0; cycle < 1000; ++cycle) {
        for (int i = 0; i < 4; ++i) assert(repeated.emit(127));
        assert(repeated.emit(0));
        repeated.consumeOne();
        assert(repeated.emit(127));
        assert(repeated.emit(0));
        assert(!repeated.releases.canAcceptPedalOn(2));
        assert(!repeated.emit(127));
        assert(repeated.emit(0));
        repeated.drain();
        const auto size = repeated.controls.size();
        assert(repeated.controls[size - 3] == 0 && repeated.controls[size - 2] == 127 && repeated.controls[size - 1] == 0);
        assert(repeated.releases.latest() == 0);
        assert(repeated.releases.canAcceptPedalOn(2));
    }
    Fixture saturated(16, 4);
    for (int i = 0; i < 12; ++i) assert(saturated.emit(127));
    assert(saturated.queue.availableWrite() == 4);
    for (int i = 0; i < 10000; ++i) assert(saturated.emit(0));
    assert(saturated.queue.availableWrite() == 4);
    Event noteOff{++saturated.next, 2, -1};
    assert(hasSynthEventQueueRoom(saturated.queue.availableWrite(), saturated.reserved, true, false));
    assert(saturated.queue.write(&noteOff, 1) == 1);
    assert(saturated.queue.availableWrite() == 3);
    saturated.drain();
    assert(saturated.controls.back() == -1);

    SpscRingBuffer<Event> multiQueue(4);
    OrderedSustainRelease multiRelease;
    std::vector<int> channelOrder;
    multiRelease.defer(9, 1);
    multiRelease.defer(2, 2);
    drainSustainOrdered<Event>(multiQueue, multiRelease, [&](const Event&) { channelOrder.push_back(99); },
        [&](int channel) { channelOrder.push_back(channel); });
    assert((channelOrder == std::vector<int>{9, 2}));
    multiRelease.defer(9, 3);
    multiRelease.defer(2, 4);
    Event laterControl{5, 2, 127};
    assert(multiQueue.write(&laterControl, 1) == 1);
    drainSustainOrdered<Event>(multiQueue, multiRelease, [&](const Event&) { channelOrder.push_back(99); },
        [&](int channel) { channelOrder.push_back(channel); });
    assert((channelOrder == std::vector<int>{9, 2, 9, 2, 99}));

}
