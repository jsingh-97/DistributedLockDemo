package io.github.jaswinder.distributedlockdemo.repository;

import io.github.jaswinder.distributedlockdemo.entity.ItemAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemAvailabilityRepository extends JpaRepository<ItemAvailability, Integer> {
}
