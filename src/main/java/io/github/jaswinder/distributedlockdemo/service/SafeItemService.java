package io.github.jaswinder.distributedlockdemo.service;

import io.github.jaswinder.distributedlockdemo.entity.ItemAvailability;
import io.github.jaswinder.distributedlockdemo.repository.ItemAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SafeItemService {
    private final RedissonClient redissonClient;
    private final SafeItemHelper safeItemHelper;

    private static final long LOCK_WAIT_SECONDS = 5L;
    private static final long LOCK_LEASE_SECONDS = 3L;
    private static final String LOCK_PREFIX = "item:lock:";

    public boolean sellItem(Integer itemId) throws InterruptedException {
        String lockKey = LOCK_PREFIX + itemId;
        String threadName = Thread.currentThread().getName();
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try{
            log.info("[{}] attempting to acquire lock with key {}", threadName, lockKey);
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if(!acquired){
                log.info("[{}] could not acquire lock within {} seconds  --aborting", threadName, LOCK_WAIT_SECONDS);
                return false;
            }
            log.info("[{}] acquired lock, entering critical section!!!!!", threadName);
            return safeItemHelper.doSell(itemId);
        }catch (Exception ex){
            log.error("[{}] exception occurred :{}", threadName, ex.getMessage());
            return false;
        }finally {
            if(acquired && lock.isHeldByCurrentThread()){
                lock.unlock();
                log.info("[{}] lock released", threadName);
            }
        }
    }

}
