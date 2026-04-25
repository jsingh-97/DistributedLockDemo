package io.github.jaswinder.distributedlockdemo.service;

import io.github.jaswinder.distributedlockdemo.entity.ItemAvailability;
import io.github.jaswinder.distributedlockdemo.repository.ItemAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SafeItemHelper {
    private final ItemAvailabilityRepository itemAvailabilityRepository;

    @Transactional
    public boolean doSell(Integer itemId) throws InterruptedException {
        String threadName = Thread.currentThread().getName();
        ItemAvailability item = itemAvailabilityRepository.findById(itemId)
                .orElseThrow(() -> new IllegalStateException("Item not found: " + itemId));
        log.info("[{}] read quantity = {}", threadName, item.getQuantity());
        Thread.sleep(1000);
        if(item.getQuantity() <= 0){
            log.info("[{}] out of stock - cannot sell", threadName);
            return false;
        }
        item.setQuantity(item.getQuantity() - 1);
        itemAvailabilityRepository.save(item);
        log.info("[{}] SOLD - quantity is now {}", threadName, item.getQuantity());
        return true;
    }
}
