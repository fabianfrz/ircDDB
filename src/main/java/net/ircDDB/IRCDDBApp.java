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

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Iterator;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Enumeration;

import java.util.Properties;

import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import java.util.Date;
import java.util.TimeZone;
import java.util.NoSuchElementException;
import java.text.SimpleDateFormat;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.FileOutputStream;




public class IRCDDBApp implements IRCApplication, Runnable
{

	IRCDDBExtApp extApp;

	IRCMessageQueue sendQ;
	Map<String,UserObject> user;
	String currentServer;
	
	String myNick;
	
	int state;
	int timer;
	
	Pattern datePattern;
	Pattern timePattern;
	Pattern[] keyPattern;
	Pattern[] valuePattern;
	Pattern tablePattern;
	Pattern hexcharPattern;
	Pattern defaultValuePattern;

	SimpleDateFormat parseDateFormat;

	String updateChannel;
	String debugChannel;

	String channelTopic;

	boolean acceptPublicUpdates;
	IRCMessageQueue publicUpdates[];

	String dumpUserDBFileName;

	int numberOfTables;
	int numberOfTablesToSync;

	Properties properties;

	Date startupTime;
	String reconnectReason;

	int channelTimeout;


	String rptrLocation;
	String[] rptrFrequencies;
	int numRptrFreq;
	String rptrInfoURL;

	
	IRCDDBApp(int numTables, Pattern[] k, Pattern[] v, String u_chan, String dbg_chan,
		IRCDDBExtApp ea, String dumpFileName)
	{
		extApp = ea;

		sendQ = null;
		currentServer = null;
		acceptPublicUpdates = false;

		numberOfTables = numTables;
		numberOfTablesToSync = numTables;
		
		publicUpdates = new IRCMessageQueue[numberOfTables];

		for (int i = 0; i < numberOfTables; i++)
		{
		  publicUpdates[i] = new IRCMessageQueue();
		}
	

		userListReset();
		
		state = 0;
		timer = 0;
		myNick = "none";
		
		parseDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		parseDateFormat.setTimeZone( TimeZone.getTimeZone("GMT"));
		
		datePattern = Pattern.compile("20[0-9][0-9]-((1[0-2])|(0[1-9]))-((3[01])|([12][0-9])|(0[1-9]))");
		timePattern = Pattern.compile("((2[0-3])|([01][0-9])):[0-5][0-9]:[0-5][0-9]");
		tablePattern = Pattern.compile("[0-9]");
		hexcharPattern = Pattern.compile("[0-9A-F]");
		defaultValuePattern = Pattern.compile("[A-Z0-9_]{8}");

		keyPattern = k;
		valuePattern = v;

		updateChannel = u_chan;
		debugChannel = dbg_chan;
		
		channelTopic = "";

		dumpUserDBFileName = dumpFileName;

		startupTime = new Date();
		reconnectReason = "startup";

		channelTimeout = 0;

		rptrLocation = null;
		rptrFrequencies = null;
		numRptrFreq = 0;
		rptrInfoURL = null;
	}

	void setParams( Properties p )
	{
	  properties = p;

	  numberOfTablesToSync = Integer.parseInt(properties.getProperty("ddb_num_tables_sync", "2"));

	  rptrInfoURL = properties.getProperty("rptr_info_url", "").trim().replaceAll("[^\\p{Graph}]", "");

	  try
	  {

	  double latitude = Double.parseDouble(properties.getProperty("rptr_pos_latitude", "0"));
	  double longitude = Double.parseDouble(properties.getProperty("rptr_pos_longitude", "0"));

	  if ((latitude != 0.0) || (longitude != 0.0))
	  {
	    String desc1 = properties.getProperty("rptr_pos_text1", "").trim().replaceAll("[^a-zA-Z0-9 +&(),./'-]", "");
	    String desc2 = properties.getProperty("rptr_pos_text2", "").trim().replaceAll("[^a-zA-Z0-9 +&(),./'-]", "");
	    String rangeUnit = properties.getProperty("rptr_range_unit", "mile").trim().toLowerCase();
	    String aglUnit = properties.getProperty("rptr_agl_unit", "meter").trim().toLowerCase();

	    
	    while (desc1.length() < 20)
	    {
	      desc1 = desc1 + " ";
	    }

	    while (desc2.length() < 20)
	    {
	      desc2 = desc2 + " ";
	    }

	    rptrLocation = String.format("%1$+09.5f %2$+010.5f", latitude, longitude).replace(',', '.') +
		" " + desc1.substring(0,20).replace(' ', '_') +
		" " + desc2.substring(0,20).replace(' ', '_');

	    String modules[] = { "A", "B", "C", "D", "AD", "BD", "CD", "DD" };

	    numRptrFreq = modules.length;

	    rptrFrequencies = new String[numRptrFreq];

	    int i;
	    for (i=0; i < numRptrFreq; i++)
	    {
	      double freq = Double.parseDouble(properties.getProperty("rptr_freq_" + modules[i] , "0"));
	      double shift = Double.parseDouble(properties.getProperty("rptr_duplex_shift_" + modules[i] , "0"));
	      double range = Double.parseDouble(properties.getProperty("rptr_range_" + modules[i] , "0"));
	      double agl = Double.parseDouble(properties.getProperty("rptr_agl_" + modules[i] , "0"));

	      if ((freq > 1.0) && (range > 0.0))
	      {
		if (rangeUnit.equals("meter") || rangeUnit.equals("meters"))
		{
		  range /= 1609.344;
		}
		else if (rangeUnit.equals("km") || rangeUnit.equals("kilometer"))
		{
		  range /= 1.609344;
		}

		if (aglUnit.equals("feet") || aglUnit.equals("foot"))
		{
		  agl *= 0.3048;
		}

		rptrFrequencies[i] = String.format("%1$s %2$011.5f %3$+010.5f %4$06.2f %5$06.1f",
		      modules[i],  freq, shift, range, agl).replace(',', '.');
	      }
	      else
	      {
		rptrFrequencies[i] = null;
	      }
	    }

	  }

	  }
	  catch (NumberFormatException e)
	  {
	    rptrFrequencies = null;
	    numRptrFreq = 0;
	    Dbg.println(Dbg.ERR, "NumberFormatException in rptr QTH/QRG" );
	  }
	  

	}
	
	
	class UserObject
	{
		String nick;
		String name;
		String host;
		boolean op;
		
		UserObject ( String n, String nm, String h )
		{
			nick = n;
			name = nm;
			host = h;
			op = false;
		}
	}
	
	public void userJoin (String nick, String name, String host)
	{
		// System.out.println("APP: join " + nick + " " + name + " " + host);
		UserObject u = new UserObject( nick, name, host );
		
		user.put( nick, u );

		if (extApp != null)
		{
			extApp.userJoin(nick, name, host);

		     if (debugChannel != null)
		     {
		       IRCMessage m2 = new IRCMessage();
		       m2.command = "PRIVMSG";
		       m2.numParams = 2;
		       m2.params[0] = debugChannel;
		       m2.params[1] = nick + ": LOGIN: " + host + " " + name;

		       IRCMessageQueue q = getSendQ();
		       if (q != null)
		       {
			  q.putMessage(m2);
		       }
		     }
		}

	}
	
	public void userLeave (String nick)
	{
		// System.out.println("APP: leave " + nick );

		if (extApp != null)
		{
			if (user.containsKey(nick))
			{
				extApp.userLeave(nick);

			     if (debugChannel != null)
			     {
			       IRCMessage m2 = new IRCMessage();
			       m2.command = "PRIVMSG";
			       m2.numParams = 2;
			       m2.params[0] = debugChannel;
			       m2.params[1] = nick + ": LOGOUT";

			       IRCMessageQueue q = getSendQ();
			       if (q != null)
			       {
				  q.putMessage(m2);
			       }
			     }
			}
		}
		
		user.remove ( nick );
		
		if (currentServer != null)
		{
			UserObject me = user.get( myNick );

			if ((me == null) || (me.op == false))  
			{
				// if I am not op, then look for new server

				if (currentServer.equals(nick))
				{
				  // currentServer = null;
				  state = 2;  // choose new server
				  timer = 200;
				  acceptPublicUpdates = false;
				  reconnectReason = nick + " left channel";
				}
			}
		}
		
	}

	public void userListReset()
	{
		user = Collections.synchronizedMap( new HashMap<String,UserObject>() );

		if (extApp != null)
		{
			extApp.userListReset();
		}
	}

	public void setCurrentNick(String nick)
	{
		myNick = nick;

		if (extApp != null)
		{
			extApp.setCurrentNick(nick);
		}
	}

	public void setTopic(String topic)
	{
		channelTopic = topic;

		if (extApp != null)
		{
			extApp.setTopic(topic);
		}
	}
	
	
	boolean findServerUser()
	{
		boolean found = false;
		
		synchronized (user)
		{
		    Collection<UserObject> v = user.values();
		    
		    Iterator<UserObject> i = v.iterator();
		    
		    while (i.hasNext())
		    {
			    UserObject u = i.next();
			    
			    // System.out.println("LIST: " + u.nick + " " + u.op);
			    
			    if (u.nick.startsWith("s-") && u.op && !myNick.equals(u.nick))
			    {
				    currentServer = u.nick;
				    found = true;
				    if (extApp != null) 
				    {
					    extApp.setCurrentServerNick(currentServer);
				    }
				    break;
			    }
		    }
		}
		
		return found;
	}
	
	
	public void userChanOp (String nick, boolean op)
	{
		UserObject u = user.get( nick );
		
		if (u != null)
		{
			// System.out.println("APP: op " + nick + " " + op);
			if ((extApp != null) && (u.op != op))
			{
				extApp.userChanOp(nick, op);
			}

			u.op = op;
		}
	}
	

	IRCDDBExtApp.UpdateResult processUpdate ( int tableID, Scanner s, String ircUser, String msg )
	{
		if (s.hasNext(datePattern))
		{
			String d = s.next(datePattern);
			
			if (s.hasNext(timePattern))
			{
				String t = s.next(timePattern);
				
				
				Date dbDate = null;

				try
				{
					dbDate = parseDateFormat.parse(d + " " + t);
				}
				catch (java.text.ParseException e)
				{
					dbDate = null;
				}
					
				if ((dbDate != null) && s.hasNext(keyPattern[tableID]))
				{
					String key = s.next(keyPattern[tableID]);
					
					
					if (s.hasNext(valuePattern[tableID]))
					{
						String value = s.next(valuePattern[tableID]);

						if (extApp != null)
						{
							return extApp.dbUpdate( tableID, dbDate, key, value, ircUser, msg );
						}
					}
				}
			}
		}
		
		return null;
	}
	 
	void enablePublicUpdates()
	{
	  acceptPublicUpdates = true;

	  for (int i = (numberOfTables-1); i >= 0; i--)
	  {
		while (publicUpdates[i].messageAvailable())
		{
			IRCMessage m = publicUpdates[i].getMessage();

			String msg = m.params[1];

                        Scanner s = new Scanner(msg);

			processUpdate(i, s, null, null);
		}
	  }
	}
	
	public void msgChannel (IRCMessage m)
	{
		// System.out.println("APP: chan");

		
		if (m.getPrefixNick().startsWith("s-"))  // server msg
		{
			int tableID = 0;

			String msg = m.params[1];
			
			Scanner s = new Scanner(msg);

			if (s.hasNext(tablePattern))
			{
			  tableID = s.nextInt();
			  if ((tableID < 0) || (tableID >= numberOfTables))
			  {
			    Dbg.println(Dbg.DBG1, "invalid table ID " + tableID);
			    return;
			  }
			}
			
			if (s.hasNext(datePattern))
			{
				if (acceptPublicUpdates)
				{
					processUpdate(tableID, s, null, null); 
				}
				else
				{
					publicUpdates[tableID].putMessage(m);
				}
			}
			else
			{
			  if (msg.startsWith("IRCDDB "))
			  {
			    channelTimeout = 0;
			  }
			  else if (extApp != null)
			  {
			    extApp.msgChannel( m );
			  }
			}
		}
	}

	private String getTableIDString( int tableID, boolean spaceBeforeNumber )
	{
	  if (tableID == 0)
	  {
	    return "";
	  }
	  else if ((tableID > 0) && (tableID < numberOfTables))
	  {
	    if (spaceBeforeNumber)
	    {
	      return " " + tableID;
	    }
	    else
	    {	
	      return tableID + " ";
	    }
	  }
	  else
	  {
	    return " TABLE_ID_OUT_OF_RANGE ";
	  }
	}


	String checkPrivCommand (String msg)
	{
	  Scanner s = new Scanner(msg);

	  String command = s.next();

	  int tableID = 0;

	  if (s.hasNext(tablePattern))
	  {
	    tableID = s.nextInt();
	  }
 
	  String d = s.next(datePattern);
	  String t = s.next(timePattern);

	  String key = s.next(keyPattern[tableID]);
          String value = s.next(valuePattern[tableID]);

	  String dummy;

	  if (s.hasNext(hexcharPattern))
	  {
	    dummy = s.next(hexcharPattern);
	  }

	  if (s.hasNext(defaultValuePattern))  // rpt2
	  {
	    dummy = s.next(defaultValuePattern);
	  }
	  else
	  {
	    return null;
	  }

	  if (s.hasNext(defaultValuePattern))  // urcall
	  {
	    String urcall = s.next(defaultValuePattern);

	    if (urcall.startsWith("PRIV"))
	    {
	      return urcall;
	    }

	    if (urcall.startsWith("VIS"))
	    {
	      return urcall;
	    }

	    if (urcall.startsWith("//"))
	    {
	      return urcall;
	    }
	  }

	  return null;
	}
	
	public void msgQuery (IRCMessage m)
	{
	
		String msg = m.params[1];
		
		Scanner s = new Scanner(msg);
		
		String command;
		
		if (s.hasNext())
		{
			command = s.next();
		}
		else
		{
			return; // no command
		}

		int tableID = 0;

		if (s.hasNext(tablePattern))
		{
		  tableID = s.nextInt();
		  if ((tableID < 0) || (tableID >= numberOfTables))
		  {
		    Dbg.println(Dbg.DBG1, "invalid table ID " + tableID);
		    return;
		  }
		}
		
		if (command.equals("UPDATE"))
		{	
			UserObject other = user.get(m.getPrefixNick()); // nick of other user
			
			if (s.hasNext(datePattern)  &&
				(other != null))
			{
				IRCDDBExtApp.UpdateResult result = processUpdate(tableID, s, other.nick, msg);
				
				if (result != null)
				{
					boolean sendUpdate = false;
					
					if (result.keyWasNew)
					{
						sendUpdate = true;
					}
					else
					{

						if (result.newObj.value.equals(result.oldObj.value)) // value is the same
						{
							long newMillis = result.newObj.modTime.getTime();
							long oldMillis = result.oldObj.modTime.getTime();
							
							if (newMillis > (oldMillis + 2400000))  // update max. every 40 min
							{
								sendUpdate = true;
							}
						}
						else
						{
							sendUpdate = true;  // value has changed, send update via channel
						}
				
					}

					UserObject me = user.get(myNick); 
					
					if ((me != null) && me.op && sendUpdate)  // send only if i am operator
					{
				
						IRCMessage m2 = new IRCMessage();
						m2.command = "PRIVMSG";
						m2.numParams = 2;
						m2.params[0] = updateChannel;
						m2.params[1] = getTableIDString(tableID, false) + 
						      parseDateFormat.format(result.newObj.modTime) + " " +
							result.newObj.key + " " + result.newObj.value + "  (from: " + m.getPrefixNick() + ")";
						
						IRCMessageQueue q = getSendQ();
						if (q != null)
						{
							q.putMessage(m2);
						}
					}

				     String privCommand = null;
				     boolean isSTNCall = false;
				     
				     if (tableID == 0)
				     {
				       isSTNCall =  result.newObj.key.startsWith("STN"); 
				       privCommand = checkPrivCommand (msg);
				     }

				     if (privCommand != null)
				     {
				       String setPriv = null;

				       if ((privCommand.equals("PRIV_ON_") || privCommand.equals("PRIV__ON"))
					    && (result.hideFromLog == false))
				       {
					 setPriv = "P_______";
				       }

				       if (privCommand.equals("PRIV_OFF")
					    && (result.hideFromLog == true))
				       {
					 setPriv = "X_______";
				       }

				       if ((privCommand.equals("VIS_OFF_") || privCommand.equals("VIS__OFF"))
					    && (result.hideFromLog == false))
				       {
					 setPriv = "P_______";
				       }

				       if ((privCommand.equals("VIS_ON__") || privCommand.equals("VIS__ON_") || privCommand.equals("VIS___ON"))
					    && (result.hideFromLog == true))
				       {
					 setPriv = "X_______";
				       }

				       if ((setPriv != null) && (me != null) && me.op
					    && (numberOfTables >= 3))  // send only if i am operator and ddb_num_tables >= 3
				       {
					  IRCMessage m2 = new IRCMessage();
					  m2.command = "PRIVMSG";
					  m2.numParams = 2;
					  m2.params[0] = updateChannel;
					  m2.params[1] = getTableIDString(2, false) + 
						parseDateFormat.format(result.newObj.modTime) + " " +
						  result.newObj.key + " " + setPriv + "  (from: " + m.getPrefixNick() + ")";
					  
					  IRCMessageQueue q = getSendQ();
					  if (q != null)
					  {
						  q.putMessage(m2);
					  }

					  if (extApp != null)
					  {
					    extApp.dbUpdate( 2, result.newObj.modTime, result.newObj.key, setPriv, myNick, null );
					  }

				       }
				     }

				     if ((debugChannel != null) && (result.modifiedLogLine != null)
					  && (privCommand == null) && ( ! isSTNCall) )
				     {
				       IRCMessage m2 = new IRCMessage();
				       m2.command = "PRIVMSG";
				       m2.numParams = 2;
				       m2.params[0] = debugChannel;
				       m2.params[1] = m.getPrefixNick() + ": UPDATE OK: " + result.modifiedLogLine;

				       IRCMessageQueue q = getSendQ();
				       if (q != null)
				       {
					  q.putMessage(m2);
				       }
				     }
				}
				else
				{
				   if (debugChannel != null)
				   {
				     IRCMessage m2 = new IRCMessage();
				     m2.command = "PRIVMSG";
				     m2.numParams = 2;
				     m2.params[0] = debugChannel;
				     m2.params[1] = m.getPrefixNick() + ": UPDATE ERROR: " + msg;

				     IRCMessageQueue q = getSendQ();
				     if (q != null)
				     {
					q.putMessage(m2);
				     }
				   }

				}
			}
			
			
	
		}
		else if (command.equals("SENDLIST"))
		{
		
			String answer = "LIST_END";
			
			if (s.hasNext(datePattern))
			{
				String d = s.next(datePattern);
				
				if (s.hasNext(timePattern))
				{
					String t = s.next(timePattern);
					
					
					Date dbDate = null;

					try
					{
						dbDate = parseDateFormat.parse(d + " " + t);
					}
					catch (java.text.ParseException e)
					{
						dbDate = null;
					}
						
					if ((dbDate != null) && (extApp != null))
					{
						final int NUM_ENTRIES = 30;

						LinkedList<IRCDDBExtApp.DatabaseObject> l = 
							extApp.getDatabaseObjects( tableID, dbDate, NUM_ENTRIES );

						int count = 0;
				
						if (l != null)
						{
						  for (IRCDDBExtApp.DatabaseObject o : l)
						  {
						    IRCMessage m3 = new IRCMessage(
							  m.getPrefixNick(),
							  "UPDATE" + getTableIDString(tableID, true) +
							    " " + parseDateFormat.format(o.modTime) + " "
							     + o.key + " " + o.value	);
					  
						    IRCMessageQueue q = getSendQ();
						    if (q != null)
						    {
						      q.putMessage(m3);
						    }
								  
						    count ++;
						  }
						}

						if (count > NUM_ENTRIES)
						{
							answer = "LIST_MORE";
						}
					}
				}
			}
			
			IRCMessage m2 = new IRCMessage();
			m2.command = "PRIVMSG";
			m2.numParams = 2;
			m2.params[0] = m.getPrefixNick();
			m2.params[1] = answer;
			
			IRCMessageQueue q = getSendQ();
			if (q != null)
			{
				q.putMessage(m2);
			}
		}
		else if (command.equals("LIST_END"))
		{
		    if (state == 5) // if in sendlist processing state
		    {
		      state = 3;  // get next table
		    }
		}
		else if (command.equals("LIST_MORE"))
		{
		    if (state == 5) // if in sendlist processing state
		    {
		      state = 4;  // send next SENDLIST
		    }
		}
		else if (command.equals("OP_BEG"))
		{
			UserObject me = user.get(myNick); 
			UserObject other = user.get(m.getPrefixNick()); // nick of other user

			if ((me != null) && (other != null) && me.op && !other.op
				&& other.nick.startsWith("s-") && me.nick.startsWith("s-") )
			{
				IRCMessage m2 = new IRCMessage();
				m2.command = "MODE";
				m2.numParams = 3;
				m2.params[0] = updateChannel;
				m2.params[1] = "+o";
				m2.params[2] = other.nick;
				
				IRCMessageQueue q = getSendQ();
				if (q != null)
				{
					q.putMessage(m2);
				}
			}
		}
		else if (command.equals("QUIT_NOW"))
		{
			UserObject other = user.get(m.getPrefixNick()); // nick of other user

			if ((other != null) && other.op
                                && other.nick.startsWith("u-"))
                        {

				IRCMessage m2 = new IRCMessage();
				m2.command = "QUIT";
				m2.numParams = 1;
				m2.params[0] = "QUIT_NOW sent by "+other.nick;

				IRCMessageQueue q = getSendQ();
				if (q != null)
				{
					q.putMessage(m2);
				}

				timer = 3;
				state = 11;  // exit
				reconnectReason = "QUIT_NOW received";
			}
		}
		else if (command.equals("SHOW_PROPERTIES"))
		{
		  UserObject other = user.get(m.getPrefixNick()); // nick of other user

		  if ((other != null) && other.op
			  && other.nick.startsWith("u-"))
		  {
		    int num = properties.size();

		    for (Enumeration e = properties.keys(); e.hasMoreElements(); )
		    {
		      String k = (String) e.nextElement();
		      String v = properties.getProperty(k);

		      if (k.equals("irc_password"))
		      {
			v = "*****";
		      }
		      else
		      {
			v = "(" + v + ")";
		      }

			IRCMessage m2 = new IRCMessage();
			m2.command = "PRIVMSG";
			m2.numParams = 2;
			m2.params[0] = m.getPrefixNick();
			m2.params[1] = num + ": (" + k + ") " + v;
			
			IRCMessageQueue q = getSendQ();
			if (q != null)
			{
				q.putMessage(m2);
			}
		      num --;
		    }
		  }
		}
		else if (command.equals("IRCDDB"))
		{
		   if (debugChannel != null)
		   {
		     IRCMessage m2 = new IRCMessage();
		     m2.command = "PRIVMSG";
		     m2.numParams = 2;
		     m2.params[0] = debugChannel;
		     m2.params[1] = m.getPrefixNick() + ": " + msg;

		     IRCMessageQueue q = getSendQ();
		     if (q != null)
		     {
			q.putMessage(m2);
		     }
		   }
		}
		else
		{
			if (extApp != null)
			{
				extApp.msgQuery(m);
			}
		}
	}
	
	
	public synchronized void setSendQ( IRCMessageQueue s )
	{
		// System.out.println("APP: setQ " + s);
		
		sendQ = s;
		
		if (extApp != null)
		{
			extApp.setSendQ(s);
		}
	}
	
	public synchronized IRCMessageQueue getSendQ ()
	{
		return sendQ;
	}
	
	
	String getLastEntryTime(int tableID)
	{

		if (extApp != null)
		{

			Date d = extApp.getLastEntryDate(tableID);

			if (d != null)
			{
				return parseDateFormat.format( d );
			}
		}
		
		return "DBERROR";
	}
	
	
	public void run()
	{

		int dumpUserDBTimer = 60;
		int sendlistTableID = 0;
		
		while (true)
		{
			
			if (timer > 0)
			{
				timer --;
			}
			
			// System.out.println("state " + state);

			channelTimeout ++;
			
			
			switch(state)
			{
			case 0:  // wait for network to start
					
				if (getSendQ() != null)
				{
					state = 1;
				}
				break;
				
			case 1:
			  // connect to db
			  state = 2;
			  timer = 200;
			  break;
			
			case 2:   // choose server
			  Dbg.println(Dbg.DBG1, "IRCDDBApp: state=2 choose new 's-'-user");
				if (getSendQ() == null)
				{
				  state = 10;
				  reconnectReason = "getSendQ in state 2";
				}
				else
				{	
					if (findServerUser())
					{
						sendlistTableID = numberOfTablesToSync;

						IRCMessage m2 = new IRCMessage();
						m2.command = "PRIVMSG";
						m2.numParams = 2;
						m2.params[0] = currentServer;
						m2.params[1] = "IRCDDB " + parseDateFormat.format(startupTime) + " " +
						    reconnectReason;
						
						IRCMessageQueue q = getSendQ();
						if (q != null)
						{
							q.putMessage(m2);
						}

						if (rptrLocation != null)
						{
						  m2 = new IRCMessage();
						  m2.command = "PRIVMSG";
						  m2.numParams = 2;
						  m2.params[0] = currentServer;
						  m2.params[1] = "IRCDDB QTH: " + rptrLocation;
						  q = getSendQ();
						  if (q != null)
						  {
							  q.putMessage(m2);
						  }
						}

						if ((rptrFrequencies != null) && (numRptrFreq > 0))
						{
						  int i;

						  for (i=0; i < numRptrFreq; i++)
						  {
						    if (rptrFrequencies[i] != null)
						    {
						      m2 = new IRCMessage();
						      m2.command = "PRIVMSG";
						      m2.numParams = 2;
						      m2.params[0] = currentServer;
						      m2.params[1] = "IRCDDB QRG: " + rptrFrequencies[i];
						      q = getSendQ();
						      if (q != null)
						      {
							      q.putMessage(m2);
						      }
						    }
						  }
						}

						if ((rptrInfoURL != null) && (rptrInfoURL.length() > 0))
						{
						  m2 = new IRCMessage();
						  m2.command = "PRIVMSG";
						  m2.numParams = 2;
						  m2.params[0] = currentServer;
						  m2.params[1] = "IRCDDB URL: " + rptrInfoURL;
						  q = getSendQ();
						  if (q != null)
						  {
							  q.putMessage(m2);
						  }
						}


						if (extApp != null)
						{
						  state = 3; // next: send "SENDLIST"
						}	
						else
						{
						  state = 6; // next: enablePublicUpdates
						}
					}
					else if (timer == 0)
					{
						state = 10;
						reconnectReason = "timeout in state 2";
						
						IRCMessage m = new IRCMessage();
						m.command = "QUIT";
						m.numParams = 1;
						m.params[0] = "no op user with 's-' found.";
						
						IRCMessageQueue q = getSendQ();
						if (q != null)
						{
							q.putMessage(m);
						}
					}
				}
				break;
				
			case 3:
				if (getSendQ() == null)
				{
				  state = 10; // disconnect DB
				  reconnectReason = "getSendQ in state 3";
				}
				else
				{
				  sendlistTableID --;
				  if (sendlistTableID < 0)
				  {
				    state = 6; // end of sendlist
				  }
				  else
				  {
				    Dbg.println(Dbg.DBG1, "IRCDDBApp: state=3 tableID="+sendlistTableID);
				    state = 4; // send "SENDLIST"
				    timer = 900; // 15 minutes max for update
				  }
				}
				break;

			case 4:
				if (getSendQ() == null)
				{
				  state = 10; // disconnect DB
				  reconnectReason = "getSendQ in state 4";
				}
				else
				{
				    if (extApp.needsDatabaseUpdate(sendlistTableID))
				    {
				      IRCMessage m = new IRCMessage();
				      m.command = "PRIVMSG";
				      m.numParams = 2;
				      m.params[0] = currentServer;
				      m.params[1] = "SENDLIST" + getTableIDString(sendlistTableID, true) 
				       + " " + getLastEntryTime(sendlistTableID);
				      
				      IRCMessageQueue q = getSendQ();
				      if (q != null)
				      {
					      q.putMessage(m);
				      }

				      state = 5; // wait for answers
				    }
				    else
				    {
				      state = 3; // don't send SENDLIST for this table, go to next table
				    }
				}
				break;

			case 5: // sendlist processing
				if (getSendQ() == null)
				{
					state = 10; // disconnect DB
					reconnectReason = "getSendQ in state 5";
				}
				else if (timer == 0)
				{
					state = 10;
					reconnectReason = "timeout in state 5";
					
					IRCMessage m = new IRCMessage();
					m.command = "QUIT";
					m.numParams = 1;
					m.params[0] = "timeout SENDLIST";
					
					IRCMessageQueue q = getSendQ();
					if (q != null)
					{
					q.putMessage(m);
					}
				}
				break;

			case 6:
				if (getSendQ() == null)
				{
					state = 10; // disconnect DB
					reconnectReason = "getSendQ in state 6";
				}
				else
				{
				  UserObject me = user.get(myNick);

				  if ((me != null) && (currentServer != null))
				  {
				    UserObject other = user.get(currentServer);

				    if ((other != null) && !me.op && other.op
					  && other.nick.startsWith("s-") && me.nick.startsWith("s-") )
				    {
				      IRCMessage m2 = new IRCMessage();
				      m2.command = "PRIVMSG";
				      m2.numParams = 2;
				      m2.params[0] = other.nick;
				      m2.params[1] = "OP_BEG";
				      
				      IRCMessageQueue q = getSendQ();
				      if (q != null)
				      {
					      q.putMessage(m2);
				      }
				    }


				    Dbg.println(Dbg.DBG1, "IRCDDBApp: state=6");
				    enablePublicUpdates();
				    state = 7;
				    channelTimeout = 0;
				  }
				}
				break;
				
			
			case 7: // standby state after initialization
				if (getSendQ() == null)
				{
				  state = 10; // disconnect DB
				  reconnectReason = "getSendQ in state 7";
				}
				else
				{
				  if (channelTimeout > 600) // 10 minutes with no IRCDDB msg in channel
				  {
				    state = 10;
				    reconnectReason = "timeout waiting for IRCDDB msg";
				  }
				}
				break;
				
			case 10:
				// disconnect db
				state = 0;
				timer = 0;
				acceptPublicUpdates = false;
				break;
			
			case 11:
				if (timer == 0)
				{
					System.exit(0);
				}
				break;
			}

			
			
			try
			{
				Thread.sleep(1000);
			}
			catch ( InterruptedException e )
			{
				Dbg.println(Dbg.WARN, "sleep interrupted " + e);
			}


			if (!dumpUserDBFileName.equals("none"))
			{
			if (dumpUserDBTimer <= 0)
			{
				dumpUserDBTimer = 300;

				try
				{
				  StringBuffer sb = new StringBuffer();

				  synchronized (user)
				  {
				    Collection<UserObject> c = user.values();

				    for (UserObject o : c)
				    {
				      sb.append( o.nick + " " + o.name +
							  " " + o.host + " " + o.op + "\n");
				    }
				  }

				  PrintWriter p = new PrintWriter(
					  new FileOutputStream(dumpUserDBFileName));

				  p.print(sb.toString());

				  p.close();


				}
				catch (IOException e)
				{
					Dbg.println(Dbg.WARN, "dumpUser failed " + e);
				}
				catch (java.util.ConcurrentModificationException e)
				{
					Dbg.println(Dbg.WARN, "dumpUser failed " + e);
					dumpUserDBTimer = 3; // try again
				}

			}
			else
			{
				dumpUserDBTimer --;
			}
			} // if (!dumpUserDBFileName.equals("none"))
		}
	}
	










/*  ----------------------------------------------- */

	public static void main (String args[])
	{

	    java.security.Security.setProperty("networkaddress.cache.ttl" , "10");
	    // we need DNS round robin, cache addresses only for 10 seconds

		String version = "";
		Package pkg;

		pkg = Package.getPackage("net.ircDDB");


		if (pkg != null)
		{

			String v = pkg.getImplementationVersion();

			if (v != null)
			{
				version = "ircDDB:" + v ;
			}
		}


		
		Properties properties = new Properties();

		try
		{ 
			properties.load (new FileInputStream("ircDDB.properties"));
		}
		catch (IOException e)
		{
			System.out.println("could not open file 'ircDDB.properties'");
			System.exit(1);
		} 

		Dbg.setDebugLevel(Integer.parseInt(properties.getProperty("debug_level", "35")));
		Dbg.println(Dbg.INFO, "Start");
		
		String irc_nick = properties.getProperty("irc_nick", "guest").trim().toLowerCase();
		String rptr_call = properties.getProperty("rptr_call", "nocall").trim().toLowerCase();

		boolean debug = properties.getProperty("debug", "0").equals("1");
		
		String n[];
		
		String irc_name;

		if (rptr_call.equals("nocall"))
		{
			n = new String[1];
			n[0] = irc_nick;
			
			irc_name = irc_nick;
		}
		else
		{
			n = new String[4];
			
			n[0] = rptr_call + "-1";
			n[1] = rptr_call + "-2";
			n[2] = rptr_call + "-3";
			n[3] = rptr_call + "-4";
			
			irc_name = rptr_call;
		}
		

		int numTables =	Integer.parseInt(properties.getProperty("ddb_num_tables", "2"));

		if ((numTables < 1) || (numTables > 10))
		{
		  Dbg.println(Dbg.ERR, "invalid ddb_num_tables: " + numTables + " must be:  1 <= x <= 10" );
		  System.exit(1);
		}

		Pattern[] keyPattern = new Pattern[numTables];
		Pattern[] valuePattern = new Pattern[numTables];
		
		try
		{
		  int i;

		  for (i=0; i < numTables; i++)
		  {
		    Pattern k = Pattern.compile(properties.getProperty("ddb_key_pattern" + i, "[A-Z0-9_]{8}"));
		    Pattern v = Pattern.compile(properties.getProperty("ddb_value_pattern" + i, "[A-Z0-9_]{8}"));

		    keyPattern[i] = k;
		    valuePattern[i] = v;
		  }

		}
		catch (PatternSyntaxException e)
		{
			Dbg.println(Dbg.ERR, "pattern syntax error " + e);
			System.exit(1);
		} 




		String extAppName = properties.getProperty("ext_app", "none");
		IRCDDBExtApp extApp = null;

		if (!extAppName.equals("none"))
		{
			try
			{
				Class extAppClass =
					Class.forName(extAppName);

				extApp = (IRCDDBExtApp) extAppClass.newInstance();

				if (extApp.setParams( properties, numTables, keyPattern, valuePattern ) == false)
				{
				  Dbg.println(Dbg.ERR, "ext_app setParams failed - exit.");
				  System.exit(1);
				}

				Thread extappthr = new Thread(extApp);

		                extappthr.start();

				pkg = extApp.getClass().getPackage();

				if (pkg != null)
				{

					String v = pkg.getImplementationVersion();

					if (v != null)
					{
						String classname = extApp.getClass().getName();
						int pos = classname.lastIndexOf('.');

						if (pos < 0)
						{
							pos = 0;
						}
						else
						{
							pos ++;
						}

						if (version.length() > 0)
						{
							version = version + " ";
						}

						version = version + classname.substring(pos) +
							":" + v ;
					}
				}

				
			}
			catch (Exception e)
			{
				Dbg.println(Dbg.ERR, "external application: " + e);
				System.exit(1);
			}
		}

		String package_version = System.getenv("PACKAGE_VERSION");

		if (package_version != null)
		{
		  if (version.length() > 0)
		  {
		     version = version + " ";
		  }

		  version = version + package_version;
		}



		
		String irc_channel = properties.getProperty("irc_channel", "#chat");
		String debug_channel = properties.getProperty("debug_channel", "none");

		if (irc_channel.equals(debug_channel))
		{
		  Dbg.println(Dbg.ERR, "irc_channel and debug_channel must not have same value");
		  System.exit(1);
		}

		if (debug_channel.equals("none"))
		{
		  debug_channel = null;
		}

		Dbg.println(Dbg.INFO, "Version " + version);
		
		IRCDDBApp app = new IRCDDBApp (numTables, keyPattern, valuePattern,
			irc_channel, debug_channel,
			extApp,
			properties.getProperty("dump_userdb_filename", "none") );
		
		app.setParams(properties);

		Thread appthr = new Thread(app);
		
		appthr.start();
		
		IRCClient irc = new IRCClient( app,
			properties.getProperty("irc_server_name", "localhost"),
			Integer.parseInt(properties.getProperty("irc_server_port", "9007")),
			irc_channel, debug_channel,
			irc_name, n,
			properties.getProperty("irc_password", "secret"), debug,
			version);

		

		Thread ircthr = new Thread(irc);

		ircthr.start();

	}



}




