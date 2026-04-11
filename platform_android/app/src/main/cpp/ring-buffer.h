// ring-buffer.h: Lock-free single-producer single-consumer ring buffer for RT audio capture.
// NOT concerned with: multi-producer, multi-consumer, or resizable buffers.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>

template <typename T>
class SpscRingBuffer {
public:
    // capacity must be power-of-2; rounds up if not
    explicit SpscRingBuffer(size_t requestedCapacity) {
        capacity_ = nextPowerOf2(requestedCapacity);
        mask_ = capacity_ - 1;
        buffer_ = new T[capacity_];
        writeIndex_.store(0, std::memory_order_relaxed);
        readIndex_.store(0, std::memory_order_relaxed);
    }

    ~SpscRingBuffer() {
        delete[] buffer_;
    }

    // Non-copyable
    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    // Write up to count items. Returns number actually written. RT-safe (no alloc, no lock).
    size_t write(const T* data, size_t count) {
        size_t avail = availableWrite();
        if (count > avail) count = avail;
        if (count == 0) return 0;

        size_t wi = writeIndex_.load(std::memory_order_relaxed);
        size_t pos = wi & mask_;

        // May need two memcpy's if wrapping around end of buffer
        size_t firstChunk = capacity_ - pos;
        if (firstChunk > count) firstChunk = count;
        std::memcpy(buffer_ + pos, data, firstChunk * sizeof(T));
        if (firstChunk < count) {
            std::memcpy(buffer_, data + firstChunk, (count - firstChunk) * sizeof(T));
        }

        writeIndex_.store(wi + count, std::memory_order_release);
        return count;
    }

    // Read up to count items. Returns number actually read.
    size_t read(T* data, size_t count) {
        size_t avail = availableRead();
        if (count > avail) count = avail;
        if (count == 0) return 0;

        size_t ri = readIndex_.load(std::memory_order_relaxed);
        size_t pos = ri & mask_;

        size_t firstChunk = capacity_ - pos;
        if (firstChunk > count) firstChunk = count;
        std::memcpy(data, buffer_ + pos, firstChunk * sizeof(T));
        if (firstChunk < count) {
            std::memcpy(data + firstChunk, buffer_, (count - firstChunk) * sizeof(T));
        }

        readIndex_.store(ri + count, std::memory_order_release);
        return count;
    }

    size_t availableRead() const {
        size_t wi = writeIndex_.load(std::memory_order_acquire);
        size_t ri = readIndex_.load(std::memory_order_relaxed);
        return wi - ri;
    }

    size_t availableWrite() const {
        size_t wi = writeIndex_.load(std::memory_order_relaxed);
        size_t ri = readIndex_.load(std::memory_order_acquire);
        return capacity_ - (wi - ri);
    }

    // Must only be called when no concurrent read/write is in progress (e.g., before
    // setting recordingActive = true). Relaxed ordering is safe under this precondition.
    void reset() {
        writeIndex_.store(0, std::memory_order_relaxed);
        readIndex_.store(0, std::memory_order_relaxed);
    }

private:
    static size_t nextPowerOf2(size_t v) {
        if (v == 0) return 1;
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v |= v >> 32;
        return v + 1;
    }

    T* buffer_;
    size_t capacity_;
    size_t mask_;
    // Indices grow monotonically; masked on access. Avoids ambiguity between full/empty.
    std::atomic<size_t> writeIndex_;
    std::atomic<size_t> readIndex_;
};
