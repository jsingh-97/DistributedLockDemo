package io.github.jaswinder.distributedlockdemo.runner;

import io.github.jaswinder.distributedlockdemo.entity.ItemAvailability;
import io.github.jaswinder.distributedlockdemo.repository.ItemAvailabilityRepository;
import io.github.jaswinder.distributedlockdemo.service.SafeItemService;
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
    private final SafeItemService safeItemService;

    static final int NUM_THREADS = 10;
    static final int ITEM_ID = 1;
    static final int ITEM_INITIAL_QTY = 1;
    @Override
    public void run(String... args) throws Exception {
        resetItemAvailability();
        final AtomicInteger successCountForUnsafe = new AtomicInteger(0);
        //Sells items using unsafe service
        ExecutorService executorService = Executors.newFixedThreadPool(NUM_THREADS);
        for(int i = 0; i < NUM_THREADS; i++){
            final int threadNum = i + 1;
            executorService.submit(() ->{
                Thread.currentThread().setName("worker-"+ threadNum);
                try {
                    boolean sold = unsafeItemService.sellItem(ITEM_ID);
                    if(sold){
                        successCountForUnsafe.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        Thread.sleep(5000);
        log.info("Unsafe item service sold item {} times", successCountForUnsafe.get());


        //Sells items using safe service
        resetItemAvailability();
        final AtomicInteger successCountForSafe = new AtomicInteger(0);
        for(int i = 0; i < NUM_THREADS; i++){
            final int threadNum = i + 1;
            executorService.submit(() ->{
                Thread.currentThread().setName("worker-"+ threadNum);
                try {
                    boolean sold = safeItemService.sellItem(ITEM_ID);
                    if(sold){
                        successCountForSafe.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        Thread.sleep(15000);
        log.info("Safe item service sold item {} times", successCountForSafe.get());
    }

    private void resetItemAvailability() {
        log.info("Setting item availability for itemId:{} to {}", ITEM_ID, ITEM_INITIAL_QTY);
        ItemAvailability item = itemAvailabilityRepository.findById(ITEM_ID).orElseGet(() ->{
             return ItemAvailability.builder().id(ITEM_ID).name("Limited Edition Sneaker").quantity(ITEM_INITIAL_QTY).build();
        });
        item.setQuantity(ITEM_INITIAL_QTY);
        itemAvailabilityRepository.save(item);
    }
}
