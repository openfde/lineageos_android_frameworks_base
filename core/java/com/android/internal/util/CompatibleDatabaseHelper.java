
package com.android.internal.util;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.database.Cursor;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import android.content.ContentValues;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.util.Slog;

import android.os.SystemProperties;


/**
	compatible db api add by xudq 

*/


public class CompatibleDatabaseHelper extends SQLiteOpenHelper {

    static final int DATABASE_VERSION = 1; 
    private static final String DATABASE_NAME = "compatible.db";

	private static final String TABLE_NAME = "COMPATIBLE_VALUE";


    private static final String COMPATIBLE_VALUE_CREATE =
            "CREATE TABLE IF NOT EXISTS COMPATIBLE_VALUE ( _ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "PACKAGE_NAME TEXT , KEY_CODE TEXT  ,VALUE TEXT  , NOTES TEXT,CREATE_DATE TEXT,EDIT_DATE TEXT,APP_NAME TEXT,FIELDS1 TEXT,FIELDS2 TEXT,IS_DEL TEXT, UNIQUE(PACKAGE_NAME, KEY_CODE))";

	private static final String COMPATIBLE_VALUE_INDEX =
				"CREATE INDEX PACKAGE_V_INDEX ON COMPATIBLE_VALUE (PACKAGE_NAME)";



    public CompatibleDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

	
	 @Override
    public void onCreate(SQLiteDatabase db) {
       createTableSQL(db);
    }

	private void createTableSQL(SQLiteDatabase db) {
		db.execSQL(COMPATIBLE_VALUE_CREATE);
		db.execSQL(COMPATIBLE_VALUE_INDEX);

	}

	private void dropTables(SQLiteDatabase db) {

		db.execSQL("DROP TABLE COMPATIBLE_VALUE");

	}

   // @Override
  //  public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
   //     onCreate(db);
   // }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }


	public List<Map<String,Object>> getAllCompatibles() {
	    SQLiteDatabase db = this.getReadableDatabase();
	    Cursor cursor = db.rawQuery("SELECT * FROM "+TABLE_NAME ,  null);
		List<Map<String, Object>> list = new ArrayList<>();
		if (cursor.moveToFirst()) {
            do {
                int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
				String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
				String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
				String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
                Map<String,Object> mp = new HashMap<>();
				mp.put("_ID",_ID);
				mp.put("PACKAGE_NAME",PACKAGE_NAME);
				mp.put("KEY_CODE",KEY_CODE);
				mp.put("VALUE",VALUE);
				mp.put("IS_DEL",IS_DEL);
				mp.put("CREATE_DATE",CREATE_DATE);
				mp.put("EDIT_DATE",EDIT_DATE);
				list.add(mp);
            } while (cursor.moveToNext());
        }
        cursor.close();
		db.close();
		return list ;
	}


	public void readCompatibles() {
			SQLiteDatabase db = this.getReadableDatabase();
			Cursor cursor = db.rawQuery("SELECT * FROM "+TABLE_NAME ,  null);
		
			if (cursor.moveToFirst()) {
				do {
					//int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
					String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
					String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
					String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
					String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
					//String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
					//String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));

					String key = PACKAGE_NAME+"_"+KEY_CODE ;
                    String value = VALUE ;
					if("1".equals(IS_DEL)){
						value = "";
					}
					SystemProperties.set(key,value);
				} while (cursor.moveToNext());
			}
			cursor.close();
			db.close();
		}

	public void readCompatibles(String packageName) {
		    Slog.wtf("parseValue", "readCompatibles...............packageName "+packageName);
			SQLiteDatabase db = this.getReadableDatabase();
			String selection = "PACKAGE_NAME = ?";
			String[] selectionArgs =  {packageName};
			Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs, null, null, null);
		
			if (cursor.moveToFirst()) {
				do {
					//int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
					String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
					String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
					String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
					String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
					//String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
					//String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));

					String key = PACKAGE_NAME+"_"+KEY_CODE ;
                    String value = VALUE ;
					if("1".equals(IS_DEL)){
						value = "";
					}
					SystemProperties.set(key,value);
				} while (cursor.moveToNext());
			}
			cursor.close();
			db.close();
		}
	
	

	
	public List<Map<String,Object>> queryCompatiblesByPackageName(String packageName) {
			SQLiteDatabase db = this.getReadableDatabase();
			String selection = "PACKAGE_NAME = ?  AND IS_DEL != 1";
			String[] selectionArgs =  {packageName};
			Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs, null, null, null);

			List<Map<String, Object>> list = new ArrayList<>();
			if (cursor.moveToFirst()) {
				do {
					int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
					String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
					String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
					String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
					String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
					String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
					String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
					Map<String,Object> mp = new HashMap<>();
					mp.put("_ID",_ID);
					mp.put("PACKAGE_NAME",PACKAGE_NAME);
					mp.put("KEY_CODE",KEY_CODE);
					mp.put("VALUE",VALUE);
					mp.put("IS_DEL",IS_DEL);
					mp.put("CREATE_DATE",CREATE_DATE);
					mp.put("EDIT_DATE",EDIT_DATE);
					list.add(mp);
				} while (cursor.moveToNext());
			}
			cursor.close();
			db.close();
			return list ;
		}


	public Map<String,Object> queryCompatibleByPackageNameAndKeyCode(String packageName,String keycode) {
			SQLiteDatabase db = this.getReadableDatabase();
			String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND IS_DEL != 1";
			String[] selectionArgs =  {packageName, keycode};
			Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs, null, null, null);

			Map<String,Object> mp = new HashMap<>();
		    if (cursor != null && cursor.moveToFirst()) {
				int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
				String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
				String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
				String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
				String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
				String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
				String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
				String FIELDS1 = cursor.getString(cursor.getColumnIndex("FIELDS1"));
				mp.put("_ID",_ID);
				mp.put("PACKAGE_NAME",PACKAGE_NAME);
				mp.put("KEY_CODE",KEY_CODE);
				mp.put("VALUE",VALUE);
				mp.put("IS_DEL",IS_DEL);
				mp.put("CREATE_DATE",CREATE_DATE);
				mp.put("EDIT_DATE",EDIT_DATE);
				mp.put("FIELDS1",FIELDS1);
			}
			cursor.close();
			db.close();
			return mp ;
		}

	public String getCompatibleByPackageNameAndKeyCode(String packageName,String keycode) {
			SQLiteDatabase db = this.getReadableDatabase();
			String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND IS_DEL != 1";
			String[] selectionArgs =  {packageName, keycode};
			Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs, null, null, null);
			
		    if (cursor != null && cursor.moveToFirst()) {
				String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
				return VALUE;
			}
			cursor.close();
			db.close();
			return null ;
		}
	

	public void insertCompatible(String packageName, String keycode, String value){
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues values = new ContentValues();
		String curTime = getCurDateTime();
	    values.put("PACKAGE_NAME", packageName);
        values.put("KEY_CODE", keycode);
        values.put("VALUE", value);
		values.put("IS_DEL", "0");
		values.put("CREATE_DATE",curTime);
		values.put("EDIT_DATE", curTime);
		values.put("FIELDS1", curTime);
	    db.insert(TABLE_NAME, null, values);
		db.close();
	}

	
	public int  updateCompatible(String packageName, String keycode, String value,String date){
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues values = new ContentValues();	
		values.put("VALUE", value);
		values.put("IS_DEL", "0");
		values.put("FIELDS1", date);
		values.put("EDIT_DATE",  getCurDateTime()); 
        int res = db.update(TABLE_NAME, values, "PACKAGE_NAME = ? AND KEY_CODE = ? ", new String[]{packageName,keycode});
		db.close();
		return res ;
	}


	public int deleteCompatible(String packageName){
		SQLiteDatabase db = this.getWritableDatabase();
	    int res =  db.delete(TABLE_NAME,   "PACKAGE_NAME = ? ", new String[]{packageName});
		db.close();
		return res ;
	}


	public int deleteCompatible(String packageName,String keycode){
		SQLiteDatabase db = this.getWritableDatabase();
	    int res =  db.delete(TABLE_NAME,   "PACKAGE_NAME = ? AND KEY_CODE = ? ", new String[]{packageName,keycode});
		db.close();
		return res ;
	}

	public void deleteAllCompatibles(){
		SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NAME, null, null); 
		db.close();
	}


	public static String getCurDateTime() {
		LocalDateTime currentTime = LocalDateTime.now();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		String formattedTime = currentTime.format(formatter);
		return formattedTime;
	}


	
	
}

