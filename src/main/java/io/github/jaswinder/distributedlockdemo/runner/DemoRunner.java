package io.github.jaswinder.distributedlockdemo.runner;

import io.github.jaswinder.distributedlockdemo.entity.ItemAvailability;
import io.github.jaswinder.distributedlockdemo.repository.ItemAvailabilityRepository;
import io.github.jaswinder.distributedlockdemo.service.UnsafeItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-runs on application startup. Executes the unsafe demo, resets the item,
 * then executes the safe demo, and prints a clear comparison at the end.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoRunner implements CommandLineRunner {
    private final UnsafeItemService unsafeItemService;
    private final ItemAvailabilityRepository itemAvailabilityRepository;
    static final int NUM_THREADS = 10;
    static final int ITEM_ID = 1;
    @Override
    public void run(String... args) throws Exception {
        resetItemAvailability();
        AtomicInteger successCount = new AtomicInteger(0);
        //Sells items using unsafe service
        ExecutorService executorService = Executors.newFixedThreadPool(NUM_THREADS);
        for(int i = 0; i < NUM_THREADS; i++){
            final int threadNum = i + 1;
            executorService.submit(() ->{
                Thread.currentThread().setName("worker-"+ threadNum);
                try {
                    boolean sold = unsafeItemService.sellItem(ITEM_ID);
                    if(sold){
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        Thread.sleep(10000);
        log.info("Unsafe item service sold item {} times", successCount.get());
    }

    private void resetItemAvailability() {
        ItemAvailability item = itemAvailabilityRepository.findById(1).orElseGet(() ->{
             return ItemAvailability.builder().id(1).name("Limited Edition Sneaker").quantity(1).build();
        });
        item.setQuantity(1);
        itemAvailabilityRepository.save(item);
    }
}
