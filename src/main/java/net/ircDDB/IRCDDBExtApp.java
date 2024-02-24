/*

ircDDB

Copyright (C) 2011   Michael Dirska, DL1BFF (dl1bff@mdx.de)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/


package net.ircDDB;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;


public interface IRCDDBExtApp extends IRCApplication, Runnable
{

	class DatabaseObject
	{
		private Instant modTime;
		private String key;
		private String value;

		public Instant getModTime() {
			return modTime;
		}

		public void setModTime(Instant modTime) {
			this.modTime = modTime;
		}

		public String getKey() {
			return key;
		}

		public void setKey(String key) {
			this.key = key;
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

	class UpdateResult
	{
		private boolean keyWasNew;
		private boolean hideFromLog;
		private String modifiedLogLine;
		private DatabaseObject newObj;
		private DatabaseObject oldObj;

		public UpdateResult()
		{
		  hideFromLog = true;
		  modifiedLogLine = null;
		}

		public boolean isKeyWasNew() {
			return keyWasNew;
		}

		public void setKeyWasNew(boolean keyWasNew) {
			this.keyWasNew = keyWasNew;
		}

		public boolean isHideFromLog() {
			return hideFromLog;
		}

		public void setHideFromLog(boolean hideFromLog) {
			this.hideFromLog = hideFromLog;
		}

		public String getModifiedLogLine() {
			return modifiedLogLine;
		}

		public void setModifiedLogLine(String modifiedLogLine) {
			this.modifiedLogLine = modifiedLogLine;
		}

		public DatabaseObject getNewObj() {
			return newObj;
		}

		public void setNewObj(DatabaseObject newObj) {
			this.newObj = newObj;
		}

		public DatabaseObject getOldObj() {
			return oldObj;
		}

		public void setOldObj(DatabaseObject oldObj) {
			this.oldObj = oldObj;
		}
	}

	boolean setParams( Properties p,  int numberOfTables,
		Pattern[] keyPattern, Pattern[] valuePattern );

	UpdateResult dbUpdate( int tableID, Instant d, String key, String value, String ircUser, String msg );

	List<DatabaseObject> getDatabaseObjects(
		int tableID, Instant beginDate, int numberOfObjects );

	Instant getLastEntryDate(int tableID);

	boolean needsDatabaseUpdate(int tableID);

	void setCurrentServerNick(String nick);
}


