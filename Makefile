# make            drop logs/ and reports/, write both logs, run the agent
# make logs       write both logs (logs/chain-divide-simple.log, logs/chain-divide.log)
# make agent      run the agent over logs/
# make clean      drop logs/ and reports/
#
# Variables: make RUNS=30, make PYTHON=python3
# Without a local JDK 25, run Java in Docker:
#   make JAVA="docker run --rm -v $(CURDIR):/w -w /w maven:3-eclipse-temurin-25 java"

JAVA   ?= java
PYTHON ?= .venv/bin/python
RUNS   ?= 20

.PHONY: demo clean logs agent

demo:
	$(MAKE) clean
	$(MAKE) logs
	$(MAKE) agent

clean:
	rm -rf logs reports

logs:
	$(JAVA) demo/SimpleLogging.java $(RUNS)
	$(JAVA) demo/ImprovedLogging.java $(RUNS)

agent:
	$(PYTHON) -m sre_agent.agent
