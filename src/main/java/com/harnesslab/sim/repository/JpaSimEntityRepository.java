package com.harnesslab.sim.repository;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaSimEntityRepository extends JpaRepository<SimEntity, String> {
}
