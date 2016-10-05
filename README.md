# WoW Legion BoE finder

## Compile
1. JDK8 needs to be installed
2. Gradle needs to be installed (unzip and set environment variable)
3. cmd : gradle jar

## Run
1. cmd : java -cp build\libs\* wow.BoEs [configFilename]
- linux/mac : java -cp build/libs/* BoEs [configFilename]
	- configFilename can be ommited, config.json will be used.
		- config.json will be automatically created if it does not exist.
2. to write to file instead of console
	java -cp build\libs\* wow.BoEs [configFilename] > [resultsFilename]

## Config
1. apikey: provide your own apikey to avoid sharing bandwidth with others.
	- blizzard limits 100 calls per second / 36k calls per hour per apikey.
2. realms: provide a list of groups of connected realms to be scanned.
	- only the first realm of each group will be used. others are for display only.
3. itemids: provide a list of itemids to be matched against, and the text to be displayed.
4. bonusids: provide a list of bonusids to be matched against, and the extra text to be displayed.
	- requireBonus: if true, bonusids have to match to display the item, otherwise it will just be used for extra text to be displayed.
5. modifierValues: provide a list of modifier types and values to be matched against. 
	- requireModifier: if true, modifier types and values have to match to display the item, otherwise it will just be used for extra text to be displayed.
	- modifiers: extra text to display for modifier types.