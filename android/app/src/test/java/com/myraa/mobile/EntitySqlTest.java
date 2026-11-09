package com.myraa.mobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** SQLite's ON CONFLICT REPLACE deletes the old row, so the id must not be relied on. */
public class EntitySqlTest {

  @Test public void entityLookupKeyIsStableAcrossScript() {
    assertEquals("shop", MemoryDb.normaliseName(" Shop "));
    assertEquals("দোকান", MemoryDb.normaliseName("দোকান"));
    assertEquals("", MemoryDb.normaliseName(null));
  }

  @Test public void entityKindsAreValidated() {
    assertEquals("contact", MemoryDb.normaliseKind("contact"));
    assertEquals("fact", MemoryDb.normaliseKind("nonsense"));
    assertEquals("fact", MemoryDb.normaliseKind(null));
  }
}
