# Authors

10122043 - Beni Lesmana
13524060 - Steven Tan
13524092 - Timothy Bernard Soeharto

# Greedy Algorithm Overview

Bot 1 uses an area-spreading strategy. Soldiers are assigned to different map quadrants based on their ID so that map coverage is distributed more evenly. Each unit then makes the best local decision available, such as painting the nearest valid tile, cleaning enemy paint, or spawning units depending on the current situation.

Bot 2 uses a ruin-first strategy. Soldiers prioritize expanding through ruins first, then building SRPs, and finally controlling territory. This greedy approach focuses on expansion and economy, with an additional spacing rule to reduce conflicts when building SRPs.

Bot 3 uses a local greedy strategy with role-based priorities. Soldiers are divided into Builder and Attacker roles, and each role follows a different action priority order. This makes expansion, SRP construction, and enemy pressure more stable and consistent.

# Battlecode 2025 Bot Project - Java

This repository contains three Java bots developed for Battlecode 2025, each implementing a different greedy strategy.

# Project Structure

README.md
Project documentation.

build.gradle
The Gradle build file used to build and run the bots.

src/
Bot source code.

test/
Test code.

client/
Contains the Battlecode client executable.

build/
Contains compiled code and other build artifacts.

matches/
Output folder for match files.

maps/
Default folder for custom maps.

gradlew, gradlew.bat
Gradle wrapper scripts for Unix/macOS/Linux and Windows.

gradle/
Files used by the Gradle wrapper.

# Getting Started

You can develop and modify the bots directly inside the src/ folder.
Each bot is implemented as a separate strategy variant for comparison and testing.

# Useful Commands

./gradlew build
Compiles the project.

./gradlew run
Runs a match using the settings in gradle.properties.

./gradlew zipForSubmit
Creates a submission zip file.

./gradlew tasks
Lists all available Gradle tasks.

# Configuration

Project-wide configuration can be found in gradle.properties.
