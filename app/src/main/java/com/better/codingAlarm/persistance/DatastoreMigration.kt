package com.better.codingAlarm.persistance

interface DatastoreMigration {
  fun drop()
  fun insertDefaultAlarms()
  fun migrateDatabase()
}
