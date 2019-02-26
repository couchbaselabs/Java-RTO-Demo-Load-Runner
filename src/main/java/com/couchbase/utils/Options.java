package com.couchbase.utils;

import static java.util.Arrays.asList;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.IOException;

public class Options {
  private OptionParser optionParser;
  private OptionSet optionSet;

  @SuppressWarnings("unused")
  private Options() { throw new AssertionError("This is not the constructor you are looking for."); }

  public Options(String... arguments) {
    optionParser = new OptionParser();

    optionParser.acceptsAll(asList("e", "execute"))
        .withRequiredArg()
        .defaultsTo("all");

    optionParser.acceptsAll(asList("b", "bucket"))
        .withRequiredArg()
        .defaultsTo("travel-sample");
    optionParser.acceptsAll(asList("c", "cluster"))
        .withRequiredArg()
        .defaultsTo("localhost");

    optionParser.acceptsAll(asList("u", "user"))
        .withRequiredArg()
        .defaultsTo("admin");
    optionParser.acceptsAll(asList("p", "password"))
        .withRequiredArg()
        .defaultsTo("password");

    optionParser.acceptsAll(asList("t", "tracer"))
        .withRequiredArg()
        .defaultsTo("threshold");
    optionParser.acceptsAll(asList("l", "log"))
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(10);
    optionParser.acceptsAll(asList("K", "kv-threshold"))
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(10);
    optionParser.acceptsAll(asList("N", "n1ql-threshold"))
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(100);

    optionParser.acceptsAll(asList("j", "jaeger-host"))
        .withRequiredArg()
        .defaultsTo("127.0.0.1");

    optionParser.acceptsAll(asList("q", "queue"))
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(5000);
    optionParser.acceptsAll(asList("d", "delay"))
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(20000);

    optionParser.acceptsAll(asList("r", "range"))
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(100);

    optionParser.acceptsAll(asList("s", "sample"))
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(10);

    optionParser.acceptsAll(asList("n", "n1ql"))
        .withRequiredArg()
        .defaultsTo("SELECT * FROM `travel-sample` WHERE type = \"hotel\";");

    optionParser.acceptsAll(asList("v", "verbose"));

    optionParser.acceptsAll(asList("h", "help"), "Display help/usage information")
        .forHelp();

    optionSet = optionParser.parse(arguments);
  }

  public void printHelp() {
    try {
      optionParser.printHelpOn(System.out);
    } catch (IOException ex) {
      System.err.println("Error printing usage - " + ex);
    }
  }

  public boolean has(String option) {
    return optionSet.has(option);
  }

  public boolean hasArgument(String option) {
    return optionSet.hasArgument(option);
  }

  public Object valueOf(String option) {
    return optionSet.valueOf(option);
  }

  public <T> T valueOf(String option, Class<T> clazz) {
    return clazz.cast(optionSet.valueOf(option));
  }

  public Integer integerValueOf(String option) {
    return valueOf(option, Integer.class);
  }

  public String stringValueOf(String option) {
    return valueOf(option, String.class);
  }
}