package com.myraa.mobile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The user's own complaint, in a test: saying "baba ke call koro" on a phone whose address book
 * has Baba, Baban, Baban-2, Bablu, Babu, Badsa, Baka and boro mama must not produce a list of
 * twelve rows. It must call father, or ask one short question and then never ask again.
 */
public class ContactMemoryTest {

  private static List<NameMatcher.Person> phoneBook() {
    List<NameMatcher.Person> people = new ArrayList<>();
    String[][] rows = {
      { "Baba", "919000000001", "1" }, { "Baban", "919000000002", "2" }, { "Baban-2", "919000000003", "3" },
      { "Bablu Roy-2", "919000000004", "4" }, { "Baby-Parlor", "919000000005", "5" },
      { "Badsa Bhandari", "919000000006", "6" }, { "Babu-Hapu Da", "919000000007", "7" },
      { "Boby Parlour", "919000000008", "8" }, { "Bablu Da-Bana", "919000000009", "9" },
      { "Bablu Roy-Gio", "919000000010", "10" }, { "Baka Kaka", "919000000011", "11" },
      { "boro mama", "919000000012", "12" }
    };
    for (String[] row : rows) people.add(new NameMatcher.Person(row[0], row[1], row[2], new ArrayList<String>()));
    return people;
  }

  @Test public void babaIsNeverAListOfTwelve() {
    NameMatcher.Decision decision = NameMatcher.resolve("baba ke call koro", phoneBook(), new ContactMemory(), new HashMap<String, String>());
    assertNotNull(decision.best);
    assertEquals("Baba", decision.best.name);
    // Either it just calls father, or it asks one short question. It must not hand over the book.
    assertTrue("verdict was " + decision.verdict, "exact".equals(decision.verdict) || "confident".equals(decision.verdict));
    assertEquals(1, decision.ranked.size());
  }

  @Test public void aLearnedWordCallsStraightAwayWithNoQuestion() {
    ContactMemory memory = new ContactMemory();
    memory.remember("baba", "Baban", "919000000002", "2");
    NameMatcher.Decision decision = NameMatcher.resolve("baba ke call koro", phoneBook(), memory, new HashMap<String, String>());
    assertEquals("exact", decision.verdict);
    assertEquals("Baban", decision.best.name);
    assertEquals("919000000002", decision.best.phone);
  }

  @Test public void learningSurvivesARestart() {
    ContactMemory memory = new ContactMemory();
    memory.remember("baba", "Baba", "919000000001", "1");
    memory.remember("baba", "Baba", "919000000001", "1");
    String saved = memory.toJson();

    ContactMemory reloaded = new ContactMemory(saved);
    NameMatcher.Decision decision = NameMatcher.resolve("baba", phoneBook(), reloaded, new HashMap<String, String>());
    assertEquals("Baba", decision.best.name);
    assertEquals(2, reloaded.lookup("baba").uses);
  }

  @Test public void aPinnedWordWinsOverACloseSpelling() {
    ContactMemory memory = new ContactMemory();
    memory.remember("baba", "Baba", "919000000001", "1");
    // "babu" is one letter away but has never been learned, so the address book decides it.
    NameMatcher.Decision babu = NameMatcher.resolve("babu", phoneBook(), memory, new HashMap<String, String>());
    assertFalse("Baba", "919000000001".equals(babu.best == null ? "" : babu.best.phone));
  }

  @Test public void aSingleMisTapCannotHijackANearMiss() {
    ContactMemory memory = new ContactMemory();
    memory.remember("baba", "Baban", "919000000002", "2");   // used once
    // One use is trusted for the exact word only; a near spelling must not reuse it yet.
    assertEquals(null, memory.lookup("babu"));
    memory.remember("baba", "Baban", "919000000002", "2");   // used twice
    assertNotNull("after two confirmations a near spelling may reuse it", memory.lookup("babu"));
  }

  @Test public void correctingAPickReplacesIt() {
    ContactMemory memory = new ContactMemory();
    memory.remember("baba", "Baban", "919000000002", "2");
    memory.remember("baba", "Baba", "919000000001", "1");
    ContactMemory.Pick pick = memory.lookup("baba");
    assertEquals("Baba", pick.name);
    assertEquals(1, memory.size());
  }

  @Test public void twoEquallyGoodDifferentPeopleAskForAWordInsteadOfAList() {
    List<NameMatcher.Person> people = Arrays.asList(
      new NameMatcher.Person("Rahul Das", "919000000021", "21", new ArrayList<String>()),
      new NameMatcher.Person("Rahul Roy", "919000000022", "22", new ArrayList<String>()));
    NameMatcher.Decision decision = NameMatcher.resolve("rahul ke call koro", people, new ContactMemory(), new HashMap<String, String>());
    if ("choose".equals(decision.verdict)) {
      assertTrue("the question must offer a word, not a list", decision.askWord);
      assertTrue(decision.ranked.size() <= 3);
    } else {
      // A clear winner is also fine, but it must be one of the two Rahuls.
      assertTrue(decision.best.name.startsWith("Rahul"));
    }
  }

  @Test public void theSamePersonWithTwoNumbersIsNotAQuestion() {
    List<NameMatcher.Person> people = Arrays.asList(
      new NameMatcher.Person("Baba", "919000000001", "1", new ArrayList<String>()),
      new NameMatcher.Person("Baba", "919000000001", "1", new ArrayList<String>()));
    NameMatcher.Decision decision = NameMatcher.resolve("baba", people, new ContactMemory(), new HashMap<String, String>());
    assertEquals("exact", decision.verdict);
  }

  @Test public void userAliasStillWorks() {
    Map<String, String> aliases = new HashMap<>();
    aliases.put(BengaliPhonetics.fold("papa"), "Baba");
    NameMatcher.Decision decision = NameMatcher.resolve("papa ke call koro", phoneBook(), new ContactMemory(), aliases);
    assertEquals("Baba", decision.best.name);
  }

  @Test public void unknownNameStaysUnknown() {
    NameMatcher.Decision decision = NameMatcher.resolve("Priyanka ke call koro", phoneBook(), new ContactMemory(), new HashMap<String, String>());
    assertEquals("none", decision.verdict);
  }

  @Test public void forgettingAWordBringsTheQuestionBack() {
    ContactMemory memory = new ContactMemory();
    memory.remember("baba", "Baba", "919000000001", "1");
    memory.forget("baba");
    assertEquals(null, memory.lookup("baba"));
    assertTrue(memory.isEmpty());
  }

  @Test public void garbageStoredValueNeverCrashes() {
    ContactMemory memory = new ContactMemory("{not json");
    assertTrue(memory.isEmpty());
    assertTrue(new ContactMemory("").isEmpty());
    assertTrue(new ContactMemory(null).isEmpty());
  }
}
