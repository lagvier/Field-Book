package com.fieldbook.tracker.database;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import com.fieldbook.tracker.R;
import com.fieldbook.tracker.activities.CollectActivity;
import com.fieldbook.tracker.brapi.model.FieldBookImage;
import com.fieldbook.tracker.brapi.model.Observation;
import com.fieldbook.tracker.database.dao.ObservationDao;
import com.fieldbook.tracker.database.dao.ObservationUnitAttributeDao;
import com.fieldbook.tracker.database.dao.ObservationUnitDao;
import com.fieldbook.tracker.database.dao.ObservationUnitPropertyDao;
import com.fieldbook.tracker.database.dao.ObservationVariableDao;
import com.fieldbook.tracker.database.dao.StudyDao;
import com.fieldbook.tracker.database.dao.VisibleObservationVariableDao;
import com.fieldbook.tracker.database.models.ObservationModel;
import com.fieldbook.tracker.database.models.ObservationUnitModel;
import com.fieldbook.tracker.database.models.ObservationVariableModel;
import com.fieldbook.tracker.database.models.StudyModel;
import com.fieldbook.tracker.objects.FieldObject;
import com.fieldbook.tracker.objects.RangeObject;
import com.fieldbook.tracker.objects.SearchData;
import com.fieldbook.tracker.objects.SearchDialogDataModel;
import com.fieldbook.tracker.objects.TraitObject;
import com.fieldbook.tracker.preferences.GeneralKeys;
import com.fieldbook.tracker.utilities.GeoJsonUtil;
import com.fieldbook.tracker.utilities.ZipUtil;

import org.phenoapps.utils.BaseDocumentTreeUtil;
import org.threeten.bp.OffsetDateTime;
import org.threeten.bp.format.DateTimeFormatter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ActivityContext;

/**
 * All database related functions are here
 */
public class DataHelper {
    public static final String RANGE = "range";
    public static final String TRAITS = "traits";
    public static final int DATABASE_VERSION = 12;
    private static final String DATABASE_NAME = "fieldbook.db";
    private static final String USER_TRAITS = "user_traits";
    private static final String EXP_INDEX = "exp_id";
    private static final String PLOTS = "plots";
    private static final String PLOT_ATTRIBUTES = "plot_attributes";
    private static final String PLOT_VALUES = "plot_values";
    public static SQLiteDatabase db;
    private static final String TAG = "Field Book";
    private static final String TICK = "`";
    private static final String TIME_FORMAT_PATTERN = "yyyy-MM-dd HH:mm:ss.SSSZZZZZ";
    private Context context;
    private SimpleDateFormat timeStamp;
    private DateTimeFormatter timeFormat;

    private OpenHelper openHelper;

    private SharedPreferences preferences;

    private Bitmap missingPhoto;

    @Inject
    public DataHelper(@ActivityContext Context context) {
        try {
            this.context = context;

            preferences = PreferenceManager.getDefaultSharedPreferences(context);

            openHelper = new OpenHelper(this);
            db = openHelper.getWritableDatabase();

            timeStamp = new SimpleDateFormat(TIME_FORMAT_PATTERN,
                    Locale.getDefault());

            timeFormat = DateTimeFormatter.ofPattern(TIME_FORMAT_PATTERN, Locale.getDefault());

            missingPhoto = BitmapFactory.decodeResource(context.getResources(), R.drawable.trait_photo_missing);

        } catch (Exception e) {
            e.printStackTrace();
            Log.w("FieldBook", "Unable to create or open database");
        }
    }

    /**
     * V9 special character delete function.
     * TODO: If we want to accept headers with special characters we need to rethink the dynamic range table.
     * @param s, the column to sanitize
     * @return output, a new string without special characters
     */
    public static String replaceSpecialChars(String s) {

        final Pattern p = Pattern.compile("[\\[\\]`\"']");

        int lastIndex = 0;

        StringBuilder output = new StringBuilder();

        Matcher matcher = p.matcher(s);

        while (matcher.find()) {

            output.append(s, lastIndex, matcher.start());

            lastIndex = matcher.end();

        }

        if (lastIndex < s.length()) {

            output.append(s, lastIndex, s.length());

        }

        return output.toString();
    }

    /**
     * Used to sanitize traits in the selection clause of raw queries.
     * Android SDK (SQLiteDatabase classes specifically) doesn't allow sanitization of columns in select clauses.
     * Because FB accepts any trait/observation unit property name and eventually pivots these into columns names in
     * DataHelper.switchField this function is necessary to manually sanitize.
     * @param s string to sanitize
     * @return string with escaped apostrophes
     */
    public static String replaceIdentifiers(String s) {

        return s.replaceAll("'", "''").replaceAll("\"", "\"\"");
    }

    /**
     * V2 - Check if a string has any special characters
     */
    public static boolean hasSpecialChars(String s) {
//        final Pattern p = Pattern.compile("[()<>/;\\*%$`\"\']");
        final Pattern p = Pattern.compile("[\\[\\]`\"']");

        final Matcher m = p.matcher(s);

        return m.find();
    }

    /**
     * Helper function to convert array to csv format
     */
    private static String convertToCommaDelimited(String[] list) {
        StringBuilder ret = new StringBuilder();
        for (int i = 0; list != null && i < list.length; i++) {
            ret.append(list[i]);
            if (i < list.length - 1) {
                ret.append(',');
            }
        }
        return ret.toString();
    }

    /**
     * Helper function to copy database
     */
    private static void copyFileCall(FileInputStream fromFile, FileOutputStream toFile) throws IOException {
        FileChannel fromChannel = null;
        FileChannel toChannel = null;

        try {
            fromChannel = fromFile.getChannel();
            toChannel = toFile.getChannel();
            fromChannel.transferTo(0, fromChannel.size(), toChannel);
        } finally {
            try {
                if (fromChannel != null) {
                    fromChannel.close();
                }
            } finally {
                if (toChannel != null) {
                    toChannel.close();
                }
            }
        }
    }

    /**
     * Issue 753, lat/lngs are saved in the incorrect order and need to be swapped
     */
    public void fixGeoCoordinates(SQLiteDatabase db) {

        db.beginTransaction();

        try {

            GeoJsonUtil.Companion.fixGeoCoordinates(this, db);

            db.setTransactionSuccessful();

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            db.endTransaction();
        }
    }

    /**
     * Populates import format based on study_source values
     */
    public void populateImportFormat(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            String updateImportFormatSQL =
                    "UPDATE studies " +
                            "SET import_format = CASE " +
                            "WHEN study_source IS NULL OR study_source = 'csv' OR study_source LIKE '%.csv' THEN 'csv' " +
                            "WHEN study_source = 'excel' OR study_source LIKE '%.xls' THEN 'xls'" +
                            "WHEN study_source LIKE '%.xlsx' THEN 'xlsx'" +
                            "ELSE 'brapi' " +
                            "END";

            db.execSQL(updateImportFormatSQL);

            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }


    /**
     * Fixes issue where BrAPI study_db_ids are saved in study_alias
     */
    public void fixStudyAliases(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            // Update `study_db_id` and `study_alias` for studies imported via 'brapi'
            String updateAliasesSQL =
                    "UPDATE studies " +
                            "SET study_db_id = study_alias, " +
                            "study_alias = study_name " +
                            "WHERE import_format = 'brapi'";

            db.execSQL(updateAliasesSQL);

            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }


    /**
     * Helper function to change visibility of a trait. Used in the ratings
     * screen
     */
    public void updateTraitVisibility(String traitDbId, boolean val) {

        open();

        ObservationVariableDao.Companion.updateTraitVisibility(traitDbId, String.valueOf(val));

//        db.execSQL("update " + TRAITS
//                + " set isVisible = ? where trait like ?", new String[]{
//                String.valueOf(val), trait});
    }

    public void updateObservationUnit(ObservationUnitModel model, String geoCoordinates) {

        open();

        ObservationUnitDao.Companion.updateObservationUnit(model, geoCoordinates);

    }

    public ObservationUnitModel[] getAllObservationUnits() {

        open();

        return ObservationUnitDao.Companion.getAll();
    }

    public ObservationUnitModel[] getAllObservationUnits(SQLiteDatabase db) {

        return ObservationUnitDao.Companion.getAll(db);
    }

    public ObservationUnitModel[] getAllObservationUnits(int studyId) {

        open();

        return ObservationUnitDao.Companion.getAll(studyId);
    }

    @Nullable
    public StudyModel getStudyById(String id) {

        open();

        return StudyDao.Companion.getById(id);
    }

    @Nullable
    public ObservationUnitModel getObservationUnitById(String id) {

        open();

        return ObservationUnitDao.Companion.getById(id);
    }

    @Nullable
    public ObservationVariableModel getObservationVariableById(String id) {

        open();

        return ObservationVariableDao.Companion.getById(id);
    }

    public String getSearchQuery(CollectActivity originActivity, List<SearchDialogDataModel> dataSet) {
        SearchQueryBuilder queryBuilder = new SearchQueryBuilder(originActivity, dataSet);
        return queryBuilder.buildSearchQuery();
    }

    /**
     * Helper function to insert user data. For example, the data entered for
     * numeric format, or date for date format The timestamp is updated within
     * this function as well
     * v1.6 - Amended to consider both trait and user data
     */
    public long insertObservation(String plotId, String traitDbId, String traitFormat, String value, String person, String location, String notes, String studyId, String observationDbId, OffsetDateTime lastSyncedTime, String rep) {

        open();

        return ObservationDao.Companion.insertObservation(plotId, traitDbId, traitFormat, value, person, location, notes, studyId, observationDbId, lastSyncedTime, rep);

//        Cursor cursor = db.rawQuery("SELECT * from user_traits WHERE user_traits.rid = ? and user_traits.parent = ?", new String[]{rid, parent});
//        int rep = cursor.getCount() + 1;
//
//        try {
//            this.insertUserTraits.bindString(1, rid);
//            this.insertUserTraits.bindString(2, parent);
//            this.insertUserTraits.bindString(3, trait);
//            this.insertUserTraits.bindString(4, userValue);
//            this.insertUserTraits.bindString(5, timeStamp.format(Calendar.getInstance().getTime()));
//            this.insertUserTraits.bindString(6, person);
//            this.insertUserTraits.bindString(7, location);
//            this.insertUserTraits.bindString(8, Integer.toString(rep));
//            this.insertUserTraits.bindString(9, notes);
//            this.insertUserTraits.bindString(10, exp_id);
//            if (observationDbId != null) {
//                this.insertUserTraits.bindString(11, observationDbId);
//            } else {
//                this.insertUserTraits.bindNull(11);
//            }
//            if (lastSyncedTime != null) {
//                this.insertUserTraits.bindString(12, lastSyncedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.getDefault())));
//            } else {
//                this.insertUserTraits.bindNull(12);
//            }
//
//            return this.insertUserTraits.executeInsert();
//        } catch (Exception e) {
//            e.printStackTrace();
//            return -1;
//        }
    }

    /**
     * Get rep of current plot/trait combination
     */
    public int getRep(String studyId, String plot, String traitDbId) {

        open();

        return ObservationDao.Companion.getRep(studyId, plot, traitDbId) + 1;

//        Cursor cursor = db.rawQuery("SELECT * from user_traits WHERE user_traits.rid = ? and user_traits.parent = ?", new String[]{plot, trait});
//        return cursor.getCount() + 1;
    }

    @NonNull
    public String getNextRep(String studyId, String unit, String traitDbId) {

        open();

        return String.valueOf(ObservationDao.Companion.getNextRepeatedValue(studyId, unit, traitDbId));

    }

    @NonNull
    public String getDefaultRep(String studyId, String unit, String traitDbId) {

        open();

        return ObservationDao.Companion.getDefaultRepeatedValue(studyId, unit, traitDbId);

    }

    public int getMaxPositionFromTraits() {

        open();

        return ObservationVariableDao.Companion.getMaxPosition();

//        int largest = 0;
//
//        Cursor cursor = db.rawQuery("select max(realPosition) from " + TRAITS, null);
//
//        if (cursor.moveToFirst()) {
//            largest = cursor.getInt(0);
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return largest;
    }

    public Boolean isBrapiSynced(String studyId, String plotId, String traitDbId, String rep) {

        open();

        return ObservationDao.Companion.isBrapiSynced(studyId, plotId, traitDbId, rep);

//        Boolean synced = false;
//        Observation o = new Observation();
//
//        Cursor cursor = db.rawQuery("SELECT observation_db_id, last_synced_time, timeTaken from user_traits WHERE user_traits.rid = ? and user_traits.parent = ?", new String[]{rid, parent});
//
//        if (cursor.moveToFirst()) {
//            o.setDbId(cursor.getString(0));
//            o.setLastSyncedTime(cursor.getString(1));
//            o.setTimestamp(cursor.getString(2));
//
//            if (o.getStatus() == Observation.Status.SYNCED || o.getStatus() == Observation.Status.EDITED) {
//                synced = true;
//            }
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return synced;
    }

    public void setTraitObservations(Integer studyId, Observation observation, Map<String,String> traitIdToTypeMap) {
        ObservationDao.Companion.insertObservation(studyId, observation, traitIdToTypeMap);
    }

    /**
     * Get user created trait observations for currently selected study
     */
    public List<Observation> getUserTraitObservations(int fieldId) {

        open();

        String studyId = Integer.toString(fieldId);

        return ObservationDao.Companion.getUserTraitObservations(studyId);

//        List<Observation> observations = new ArrayList<>();
//
//        // get currently selected study
//        String exp_id = Integer.toString(ep.getInt("SelectedFieldExpId", 0));
//
//        String query = "SELECT " +
//                "user_traits.id, " +
//                "user_traits.userValue " +
//                "FROM " +
//                "user_traits " +
//                "JOIN " +
//                "traits ON user_traits.parent = traits.trait " +
//                "WHERE " +
//                "(traits.trait_data_source = 'local' OR traits.trait_data_source IS NULL)" +
//                "AND " +
//                "traits.format <> 'photo' " +
//                "AND " +
//                "user_traits.exp_id = " + exp_id + ";";
//
//        Cursor cursor = db.rawQuery(query, null);
//
//        if (cursor.moveToFirst()) {
//            do {
//                Observation o = new Observation();
//                o.setFieldbookDbId(cursor.getString(0));
//                o.setValue(cursor.getString(1));
//                observations.add(o);
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return observations;
    }

    /**
     * Get user created trait observations for currently selected study
     */
    public List<FieldBookImage> getUserTraitImageObservations(Context ctx, int fieldId) {

        open();

        String studyId = Integer.toString(fieldId);

        return ObservationDao.Companion.getUserTraitImageObservations(ctx, studyId, missingPhoto);

//        List<Image> images = new ArrayList<>();
//
//        // get currently selected study
//        String exp_id = Integer.toString(ep.getInt("SelectedFieldExpId", 0));
//
//        String query = "SELECT " +
//                "user_traits.id, " +
//                "user_traits.userValue " +
//                "FROM " +
//                "user_traits " +
//                "JOIN " +
//                "traits ON user_traits.parent = traits.trait " +
//                "WHERE " +
//                "(traits.trait_data_source = 'local' OR traits.trait_data_source IS NULL)" +
//                "AND " +
//                "traits.format = 'photo' " +
//                "AND " +
//                "user_traits.exp_id = " + exp_id + ";";
//
//        Cursor cursor = db.rawQuery(query, null);
//
//        if (cursor.moveToFirst()) {
//            do {
//                Image image = new Image(cursor.getString(1), missingPhoto);
//                image.setFieldbookDbId(cursor.getString(0));
//                images.add(image);
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return images;
    }

    public List<Observation> getWrongSourceObservations(String hostUrl) {

        open();

        return ObservationDao.Companion.getWrongSourceObservations(hostUrl);

//        List<Observation> observations = new ArrayList<>();
//
//        String query = String.format("SELECT " +
//                "user_traits.id, " +
//                "user_traits.userValue " +
//                "FROM " +
//                "user_traits " +
//                "JOIN " +
//                "traits ON user_traits.parent = traits.trait " +
//                "JOIN " +
//                "exp_id ON user_traits.exp_id = exp_id.exp_id " +
//                "WHERE " +
//                "exp_id.exp_source IS NOT NULL " +
//                "AND " +
//                "traits.trait_data_source <> '%s' " +
//                "AND " +
//                "traits.trait_data_source <> 'local' " +
//                "AND " +
//                "traits.trait_data_source IS NOT NULL " +
//                "AND " +
//                "traits.format <> 'photo'", hostUrl);
//
//        Cursor cursor = db.rawQuery(query, null);
//
//        if (cursor.moveToFirst()) {
//            do {
//                Observation o = new Observation();
//                o.setFieldbookDbId(cursor.getString(0));
//                o.setValue(cursor.getString(1));
//                observations.add(o);
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return observations;
    }

    public List<FieldBookImage> getWrongSourceImageObservations(Context ctx, String hostUrl) {

        open();

        return ObservationDao.Companion.getWrongSourceImageObservations(ctx, hostUrl, missingPhoto);

//        List<Image> images = new ArrayList<>();
//
//        String query = String.format("SELECT " +
//                "user_traits.id, " +
//                "user_traits.userValue " +
//                "FROM " +
//                "user_traits " +
//                "JOIN " +
//                "traits ON user_traits.parent = traits.trait " +
//                "JOIN " +
//                "exp_id ON user_traits.exp_id = exp_id.exp_id " +
//                "WHERE " +
//                "exp_id.exp_source IS NOT NULL " +
//                "AND " +
//                "traits.trait_data_source <> '%s' " +
//                "AND " +
//                "traits.trait_data_source <> 'local' " +
//                "AND " +
//                "traits.trait_data_source IS NOT NULL " +
//                "AND " +
//                "traits.format = 'photo'", hostUrl);
//
//        Cursor cursor = db.rawQuery(query, null);
//
//        if (cursor.moveToFirst()) {
//            do {
//                Image image = new Image(cursor.getString(1), missingPhoto);
//                image.setFieldbookDbId(cursor.getString(0));
//                images.add(image);
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return images;
    }

    /**
     * Get the data for brapi export to external system
     */
    public List<Observation> getObservations(int fieldId, String hostUrl) {

        open();

        return ObservationDao.Companion.getObservations(fieldId, hostUrl);

//        List<Observation> observations = new ArrayList<Observation>();
//
//        // Get only the data that belongs to the system we are importing to.
//        String query = "SELECT " +
//                "range.observationUnitDbId, " +
//                "range.observationUnitName, " +
//                "traits.external_db_id, " +
//                "user_traits.timeTaken, " +
//                "user_traits.userValue, " +
//                "traits.trait, " +
//                "exp_id.exp_alias, " +
//                "user_traits.id, " +
//                "user_traits.observation_db_id, " +
//                "user_traits.last_synced_time, " +
//                "user_traits.person " +
//                "FROM " +
//                "user_traits " +
//                "JOIN " +
//                "range ON user_traits.rid = range.observationUnitDbId " +
//                "JOIN " +
//                "traits ON user_traits.parent = traits.trait " +
//                "JOIN " +
//                "exp_id ON user_traits.exp_id = exp_id.exp_id " +
//                "WHERE " +
//                "exp_id.exp_source IS NOT NULL " +
//                "AND " +
//                String.format("traits.trait_data_source = '%s' ", hostUrl) +
//                "AND " +
//                "user_traits.userValue <> '' " +
//                "AND " +
//                "traits.trait_data_source IS NOT NULL " +
//                "AND " +
//                "traits.format <> 'photo'";
//
//        Cursor cursor = db.rawQuery(query, null);
//
//        if (cursor.moveToFirst()) {
//            do {
//                Observation o = new Observation();
//
//                o.setUnitDbId(cursor.getString(0));
//                o.setVariableDbId(cursor.getString(2));
//                o.setTimestamp(cursor.getString(3));
//                o.setValue(cursor.getString(4));
//                o.setVariableName(cursor.getString(5));
//                o.setStudyId(cursor.getString(6));
//                o.setFieldbookDbId(cursor.getString(7));
//                o.setDbId(cursor.getString(8));
//                o.setLastSyncedTime(cursor.getString(9));
//                o.setCollector(cursor.getString(10));
//
//                observations.add(o);
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return observations;
    }

    /**
     * Get the image observations for brapi export to external system
     */
    public List<FieldBookImage> getImageObservations(Context ctx, String hostUrl) {

        open();

        return ObservationDao.Companion.getHostImageObservations(ctx, hostUrl, missingPhoto);

//        List<Image> images = new ArrayList<Image>();
//
//        // Get only the data that belongs to the system we are importing to.
//        String query = "SELECT " +
//                "range.observationUnitDbId, " +
//                "range.observationUnitName, " +
//                "traits.external_db_id, " +
//                "user_traits.timeTaken, " +
//                "user_traits.userValue, " +
//                "traits.trait, " +
//                "exp_id.exp_alias, " +
//                "user_traits.id, " +
//                "user_traits.observation_db_id, " +
//                "user_traits.last_synced_time, " +
//                "user_traits.person, " +
//                "traits.details " +
//                "FROM " +
//                "user_traits " +
//                "JOIN " +
//                "range ON user_traits.rid = range.observationUnitDbId " +
//                "JOIN " +
//                "traits ON user_traits.parent = traits.trait " +
//                "JOIN " +
//                "exp_id ON user_traits.exp_id = exp_id.exp_id " +
//                "WHERE " +
//                "exp_id.exp_source IS NOT NULL " +
//                "AND " +
//                String.format("traits.trait_data_source = '%s' ", hostUrl) +
//                "AND " +
//                "user_traits.userValue <> '' " +
//                "AND " +
//                "traits.trait_data_source IS NOT NULL " +
//                "AND " +
//                "traits.format = 'photo'";
//
//        Cursor cursor = db.rawQuery(query, null);
//
//        if (cursor.moveToFirst()) {
//            do {
//                // Instantiate our image with our file path. Which is stored in the userValue.
//                Image image = new Image(cursor.getString(4), missingPhoto);
//
//                // Assign the rest of our values
//                image.setUnitDbId(cursor.getString(0));
//
//                List<String> descriptiveOntologyTerms = new ArrayList<>();
//                descriptiveOntologyTerms.add(cursor.getString(2));
//                image.setDescriptiveOntologyTerms(descriptiveOntologyTerms);
//
//                // Set image decription the same as our trait description.
//                image.setDescription(cursor.getString(11));
//
//
//                image.setTimestamp(cursor.getString(3));
//                image.setFieldbookDbId(cursor.getString(7));
//                image.setDbId(cursor.getString(8));
//                image.setLastSyncedTime(cursor.getString(9));
//
//
//                images.add(image);
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return images;
    }

    public void updateObservationUnitModels(List<ObservationUnitModel> models) {

        open();

        ObservationUnitDao.Companion.updateObservationUnitModels(models);
    }

    public void updateObservationUnitModels(SQLiteDatabase db, List<ObservationUnitModel> models) {

        ObservationUnitDao.Companion.updateObservationUnitModels(db, models);
    }

    public void updateObservationModels(List<ObservationModel> observations) {

        open();

        ObservationDao.Companion.updateObservationModels(observations);

    }

    public void updateObservationModels(SQLiteDatabase db, List<ObservationModel> observations) {

        ObservationDao.Companion.updateObservationModels(db, observations);

    }

    public void updateObservation(ObservationModel observation) {

        open();

        ObservationDao.Companion.updateObservation(observation);

    }

    /**
     * Sync with observationdbids BrAPI
     */
    public void updateObservations(List<Observation> observations) {

        open();

        ObservationDao.Companion.updateObservations(observations);

//        ArrayList<String> ids = new ArrayList<String>();
//
//        db.beginTransaction();
//        String sql = "UPDATE user_traits SET observation_db_id = ?, last_synced_time = ? WHERE id = ?";
//        SQLiteStatement update = db.compileStatement(sql);
//
//        for (Observation observation : observations) {
//            update.bindString(1, observation.getDbId());
//            update.bindString(2, observation.getLastSyncedTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.getDefault())));
//            update.bindString(3, observation.getFieldbookDbId());
//            update.execute();
//        }
//
//        db.setTransactionSuccessful();
//        db.endTransaction();
    }

    public List<String> getPossibleUniqueAttributes(int studyId) {
        open();
        return StudyDao.Companion.getPossibleUniqueAttributes(studyId);
    }

    public void updateSearchAttribute(int studyId, String newSearchAttribute) {
        open();
        StudyDao.Companion.updateSearchAttribute(studyId, newSearchAttribute);
    }
    
    public int updateSearchAttributeForAllFields(String newSearchAttribute) {
        open();
        return StudyDao.Companion.updateSearchAttributeForAllFields(newSearchAttribute);
    }

    public ObservationUnitModel[] getObservationUnitsBySearchAttribute(int studyId, String searchValue) {
        open();
        return ObservationUnitDao.Companion.getBySearchAttribute(studyId, searchValue);
    }

    public void updateImages(List<FieldBookImage> images) {
        ArrayList<String> ids = new ArrayList<>();

        db.beginTransaction();
        String sql = "UPDATE user_traits SET observation_db_id = ?, last_synced_time = ? WHERE id = ?";
        SQLiteStatement update = db.compileStatement(sql);

        for (FieldBookImage image : images) {
            update.bindString(1, image.getDbId());
            update.bindString(2, image.getLastSyncedTime().format(timeFormat));
            update.bindString(3, image.getFieldbookDbId());
            update.execute();
        }

        db.setTransactionSuccessful();
        db.endTransaction();
    }

    public void updateImage(FieldBookImage image, Boolean writeLastSyncedTime) {

        open();

        ObservationDao.Companion.updateImage(image, writeLastSyncedTime);

//        db.beginTransaction();
//        String sql;
//        if (writeLastSyncedTime) {
//            sql = "UPDATE user_traits SET observation_db_id = ?, last_synced_time = ? WHERE id = ?";
//        } else {
//            sql = "UPDATE user_traits SET observation_db_id = ? WHERE id = ?";
//        }
//
//        SQLiteStatement update = db.compileStatement(sql);
//
//        update.bindString(1, image.getDbId());
//        if (writeLastSyncedTime) {
//            update.bindString(2, image.getLastSyncedTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ", Locale.getDefault())));
//            update.bindString(3, image.getFieldbookDbId());
//        } else {
//            update.bindString(2, image.getFieldbookDbId());
//        }
//
//        update.execute();
//
//        db.setTransactionSuccessful();
//        db.endTransaction();
    }

    /**
     * Helper function to close the database
     */
    public void close() {
        try {
            db.close();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Helper function to open the database
     */
    public void open() {

        try {
            db = openHelper.getWritableDatabase();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * V2 - Convert array to String
     */
    private String arrayToString(String table, String[] s) {
        String value = "";

        for (int i = 0; i < s.length; i++) {
            if (table.length() > 0)
                value += table + "." + TICK + s[i] + TICK;
            else
                value += s[i];

            if (i < s.length - 1)
                value += ",";
        }

        return value;
    }

    /**
     * Retrieves the columns needed for database export with all imported attributes
     */
    public Cursor getExportDBData(String[] fieldList, ArrayList<TraitObject> traits, int fieldId) {

        open();
        return ObservationUnitPropertyDao.Companion.getExportDbData(
                context, fieldId, fieldList, traits);

    }

    /**
     * Retrieves the columns needed for database export with only unique identifier
     */
    public Cursor getExportDBDataShort(String[] fieldList, String uniqueId, ArrayList<TraitObject> traits, int fieldId) {

        open();
        return ObservationUnitPropertyDao.Companion.getExportDbDataShort(
                context, fieldId, fieldList, uniqueId, traits);

    }

    private String arrayToLikeString(String[] visibleTrait) {
        String value = "(";

        for (int i = 0; i < visibleTrait.length; i++) {
            //TODO replace apostrophes with ticks
            value += "user_traits.parent like '" + visibleTrait[i] + "'";
            if (i != visibleTrait.length - 1) {
                value += " or ";
            }
        }

        value += ")";
        return value;
    }

    /**
     * Same as convertDatabaseToTable but filters by obs unit
     */
    public Cursor convertDatabaseToTable(String[] col, ArrayList<TraitObject> traits, String obsUnit) {

        open();

        return ObservationUnitPropertyDao.Companion.convertDatabaseToTable(
                preferences.getInt(GeneralKeys.SELECTED_FIELD_ID, -1),
                preferences.getString(GeneralKeys.UNIQUE_NAME, ""),
                obsUnit,
                col,
                traits.toArray(new TraitObject[]{}));

    }

    /**
     * Convert EAV database to relational
     * TODO add where statement for repeated values
     */
    public Cursor getExportTableDataShort(int fieldId,  String uniqueId, ArrayList<TraitObject> traits) {

        open();
        return ObservationUnitPropertyDao.Companion.getExportTableDataShort(context, fieldId, uniqueId, traits);

    }

    public Cursor getExportTableData(int fieldId, ArrayList<TraitObject> traits) {

        open();
        return ObservationUnitPropertyDao.Companion.getExportTableData(context, fieldId, traits);

    }

    /**
     * Used by the application to return all traits which are visible
     */
    public String[] getVisibleTrait(String sortOrder) {

        open();

        return VisibleObservationVariableDao.Companion.getVisibleTrait(sortOrder);

//        String[] data = null;
//
//        Cursor cursor = db.query(TRAITS, new String[]{"id", "trait", "realPosition"},
//                "isVisible like ?", new String[]{"true"}, null, null, "realPosition");
//
//        int count = 0;
//
//        if (cursor.moveToFirst()) {
//            data = new String[cursor.getCount()];
//
//            do {
//                data[count] = cursor.getString(1);
//
//                count += 1;
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    public ArrayList<TraitObject> getVisibleTraitObjects(String sortOrder) {

        open();

        return VisibleObservationVariableDao.Companion.getVisibleTraitObjects(sortOrder);
    }

    /**
     * Used by application to loops through formats which are visible
     */
    public String[] getFormat() {

        open();

        return VisibleObservationVariableDao.Companion.getFormat();

//        String[] data = null;
//
//        Cursor cursor = db.query(TRAITS, new String[]{"id", "format", "realPosition"},
//                "isVisible like ?", new String[]{"true"}, null, null, "realPosition");
//
//        int count = 0;
//
//        if (cursor.moveToFirst()) {
//            data = new String[cursor.getCount()];
//
//            do {
//                data[count] = cursor.getString(1);
//
//                count += 1;
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Returns all traits regardless of visibility. Used by the ratings screen
     */
    public String[] getAllTraits() {

        open();

        return ObservationVariableDao.Companion.getAllTraits();

//        String[] data = null;
//
//        Cursor cursor = db.query(TRAITS, new String[]{"id", "trait", "realPosition"},
//                null, null, null, null, "realPosition");
//
//        int count = 0;
//
//        if (cursor.moveToFirst()) {
//            data = new String[cursor.getCount()];
//
//            do {
//                data[count] = cursor.getString(1);
//
//                count += 1;
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Get data from specific column of trait table to reorder
     */
    public String[] getTraitColumnData(String column) {

        open();

        return ObservationVariableDao.Companion.getTraitColumnData(column);

//        String[] data = null;
//
//        Cursor cursor = db.query(TRAITS, new String[]{column},
//                null, null, null, null, null);
//
//        int count = 0;
//
//        if (cursor.moveToFirst()) {
//            data = new String[cursor.getCount()];
//
//            do {
//                data[count] = cursor.getString(0);
//
//                count += 1;
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Write new realPosition
     */
    public void writeNewPosition(String column, String id, String position) {

        open();

        ObservationVariableDao.Companion.writeNewPosition(column, id, position);

//        ContentValues cv = new ContentValues();
//        cv.put("realPosition", position);
//        db.update(TRAITS, cv, column + "= ?", new String[]{id});
    }

    /**
     * V2 - Returns all traits column titles as a string array
     */
    public String[] getTraitColumns() {

        return ObservationVariableDao.Companion.getTraitColumns();

//        Cursor cursor = db.rawQuery("SELECT * from traits limit 1", null);
//
//        String[] data = null;
//        HashSet<String> excludedColumns = new HashSet<>();
//
//        excludedColumns.add("id");
//        excludedColumns.add("external_db_id");
//        excludedColumns.add("trait_data_source");
//
//        if (cursor.moveToFirst()) {
//            int i = cursor.getColumnCount() - excludedColumns.size();
//
//            data = new String[i];
//
//            int k = 0;
//
//            for (int j = 0; j < cursor.getColumnCount(); j++) {
//                if (!excludedColumns.contains(cursor.getColumnName(j))) {
//                    data[k] = cursor.getColumnName(j);
//                    k += 1;
//                }
//            }
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * V2 - Returns all traits column titles as a cursor
     */
    public Cursor getAllTraitsForExport() {

        open();

        return ObservationVariableDao.Companion.getAllTraitsForExport();

//        Cursor cursor = db.query(TRAITS, getTraitColumns(),
//                null, null, null, null, "id");
//
//        return cursor;
    }

    public Cursor getAllTraitObjectsForExport() {

        open();

        return ObservationVariableDao.Companion.getAllTraitObjectsForExport();
    }

    /**
     * V4 - Get all traits in the system, in order, as TraitObjects
     */
    public ArrayList<FieldObject> getAllFieldObjects() {

        open();

        return StudyDao.Companion.getAllFieldObjects(
                preferences.getString(GeneralKeys.FIELDS_LIST_SORT_ORDER, "date_import")
        );

//        ArrayList<FieldObject> list = new ArrayList<>();
//
//        Cursor cursor = db.query(EXP_INDEX, new String[]{"exp_id", "exp_name", "unique_id", "primary_id",
//                        "secondary_id", "date_import", "date_edit", "date_export", "count", "exp_source"},
//                null, null, null, null, "exp_id"
//        );
//
//        if (cursor.moveToFirst()) {
//            do {
//                FieldObject o = new FieldObject();
//                o.setExp_id(cursor.getInt(0));
//                o.setExp_name(cursor.getString(1));
//                o.setUnique_id(cursor.getString(2));
//                o.setPrimary_id(cursor.getString(3));
//                o.setSecondary_id(cursor.getString(4));
//                o.setDate_import(cursor.getString(5));
//                o.setDate_edit(cursor.getString(6));
//                o.setDate_export(cursor.getString(7));
//                o.setCount(cursor.getString(8));
//                o.setExp_source(cursor.getString(9));
//                list.add(o);
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return list;
    }

    public FieldObject getFieldObject(Integer studyId) {

        open();

        return StudyDao.Companion.getFieldObject(
                studyId,
                preferences.getString(GeneralKeys.TRAITS_LIST_SORT_ORDER, "position")
        );

//        Cursor cursor = db.query(EXP_INDEX, new String[]{"exp_id", "exp_name", "unique_id", "primary_id",
//                        "secondary_id", "date_import", "date_edit", "date_export", "count", "exp_source"},
//                String.format("exp_id = %s", exp_id), null, null, null, "exp_id"
//        );
//
//        if (cursor.moveToFirst()) {
//            do {
//                FieldObject o = new FieldObject();
//                o.setExp_id(cursor.getInt(0));
//                o.setExp_name(cursor.getString(1));
//                o.setUnique_id(cursor.getString(2));
//                o.setPrimary_id(cursor.getString(3));
//                o.setSecondary_id(cursor.getString(4));
//                o.setDate_import(cursor.getString(5));
//                o.setDate_edit(cursor.getString(6));
//                o.setDate_export(cursor.getString(7));
//                o.setCount(cursor.getString(8));
//                o.setExp_source(cursor.getString(9));
//                return o;
//            } while (cursor.moveToNext());
//        } else {
//            // If we have no results, return null.
//            return null;
//        }

    }

    /**
     * V2 - Get all traits in the system, in order, as TraitObjects
     */
    public ArrayList<TraitObject> getAllTraitObjects() {

        open();

        return ObservationVariableDao.Companion.getAllTraitObjects(
                preferences.getString(GeneralKeys.TRAITS_LIST_SORT_ORDER, "position")
        );

//        ArrayList<TraitObject> list = new ArrayList<>();
//
//        Cursor cursor = db.query(TRAITS, new String[]{"id", "trait", "format", "defaultValue",
//                        "minimum", "maximum", "details", "categories", "isVisible", "realPosition"},
//                null, null, null, null, "realPosition"
//        );
//
//        if (cursor.moveToFirst()) {
//            do {
//                TraitObject o = new TraitObject();
//
//                o.setId(cursor.getString(0));
//                o.setTrait(cursor.getString(1));
//                o.setFormat(cursor.getString(2));
//                o.setDefaultValue(cursor.getString(3));
//                o.setMinimum(cursor.getString(4));
//                o.setMaximum(cursor.getString(5));
//                o.setDetails(cursor.getString(6));
//                o.setCategories(cursor.getString(7));
//                o.setRealPosition(cursor.getString(9));
//
//                list.add(o);
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return list;
    }

    /**
     * Returns all traits regardless of visibility, but as a hashmap
     */
    public HashMap<String, String> getTraitVisibility() {

        open();

        return ObservationVariableDao.Companion.getTraitVisibility();

//        HashMap data = new HashMap();
//
//        Cursor cursor = db.query(TRAITS, new String[]{"id", "trait",
//                "isVisible", "realPosition"}, null, null, null, null, "realPosition");
//
//        if (cursor.moveToFirst()) {
//            do {
//                data.put(cursor.getString(1), cursor.getString(2));
//
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Returns a particular trait as an object
     */
    public TraitObject getDetail(String trait) {

        open();

        return VisibleObservationVariableDao.Companion.getDetail(trait);

//        TraitObject data = new TraitObject();
//
//        data.setTrait("");
//        data.setFormat("");
//        data.setDefaultValue("");
//        data.setMinimum("");
//        data.setMaximum("");
//        data.setDetails("");
//        data.setCategories("");
//
//        Cursor cursor = db.query(TRAITS, new String[]{"trait", "format", "defaultValue", "minimum",
//                        "maximum", "details", "categories", "id", "external_db_id"}, "trait like ? and isVisible like ?",
//                new String[]{trait, "true"}, null, null, null
//        );
//
//        if (cursor.moveToFirst()) {
//            data.setTrait(cursor.getString(0));
//            data.setFormat(cursor.getString(1));
//            data.setDefaultValue(cursor.getString(2));
//            data.setMinimum(cursor.getString(3));
//            data.setMaximum(cursor.getString(4));
//            data.setDetails(cursor.getString(5));
//            data.setCategories(cursor.getString(6));
//            data.setId(cursor.getString(7));
//            data.setExternalDbId(cursor.getString(8));
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Returns saved data based on plot_id
     * v1.6 - Amended to consider both trait and format
     */
    public HashMap<String, String> getUserDetail(String plotId) {

        open();

        String studyId = Integer.toString(preferences.getInt(GeneralKeys.SELECTED_FIELD_ID, 0));

        return ObservationDao.Companion.getUserDetail(studyId, plotId);

//        HashMap data = new HashMap();
//
//        Cursor cursor = db.query(USER_TRAITS, new String[]{"parent", "trait",
//                        "userValue", "rid"}, "rid like ?", new String[]{plotId},
//                null, null, null
//        );
//
//        if (cursor.moveToFirst()) {
//            do {
//                data.put(cursor.getString(0), cursor.getString(2));
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Get observation data that needs to be saved on edits
     */
    public Observation getObservation(String studyId, String plotId, String traitDbId, String rep) {

        open();

        return ObservationDao.Companion.getObservation(studyId, plotId, traitDbId, rep);

        //        Cursor cursor = db.query(USER_TRAITS, new String[]{"observation_db_id", "last_synced_time"}, "rid like ? and parent like ?", new String[]{plotId, parent},
//                null, null, null
//        );

//        if (cursor.moveToFirst()) {
//            do {
//                o.setDbId(cursor.getString(0));
//                o.setLastSyncedTime(cursor.getString(1));
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }

    }

    public Observation getObservationByValue(String studyId, String plotId, String traitDbId, String value) {

        open();

        return ObservationDao.Companion.getObservationByValue(studyId, plotId, traitDbId, value);

//        Observation o = new Observation();
//
//        Cursor cursor = db.query(USER_TRAITS, new String[]{"observation_db_id", "last_synced_time"}, "rid like ? and parent like ? and userValue like ?", new String[]{plotId, parent, value},
//                null, null, null
//        );
//
//        if (cursor.moveToFirst()) {
//            do {
//                o.setDbId(cursor.getString(0));
//                o.setLastSyncedTime(cursor.getString(1));
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return o;
    }

    /**
     * Check if a trait exists within the database
     * v1.6 - Amended to consider both trait and format
     */
    public boolean getTraitExists(int plotId, String traitDbId) {

        open();

        return ObservationVariableDao.Companion.getTraitExists(preferences.getString(GeneralKeys.UNIQUE_NAME, ""), plotId, traitDbId);

//        boolean haveData = false;
//
//        Cursor cursor = db
//                .rawQuery(
//                        "select range.id, user_traits.userValue from user_traits, range where " +
//                                "user_traits.rid = range." + TICK + ep.getString("ImportUniqueName", "") + TICK +
//                                " and range.id = ? and user_traits.parent like ? and user_traits.trait like ?",
//                        new String[]{String.valueOf(id), parent, trait}
//                );
//
//        if (cursor.moveToFirst()) {
//            if (cursor.getString(1) != null) {
//                haveData = true;
//            }
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return haveData;
    }

    /**
     * Returns the primary key for all ranges
     */
    public int[] getAllRangeID() {

        open();

        if (!isTableExists("ObservationUnitProperty")) {

            ArrayList<FieldObject> fields = StudyDao.Companion.getAllFieldObjects(
                    preferences.getString(GeneralKeys.FIELDS_LIST_SORT_ORDER, "date_import")
            );

            if (!fields.isEmpty()) {

                StudyDao.Companion.switchField(fields.get(0).getExp_id());

            }
        }

        Integer[] result = ObservationUnitPropertyDao.Companion.getAllRangeId(context);

        int[] data = new int[result.length];

        int count = 0;

        for (Integer i : result) {
            data[count++] = i;
        }

        return data;

//        //TODO check for range table, if not exist create
//        Cursor cursor = db.query(RANGE, new String[]{"id"}, null, null,
//                null, null, "id");
//
//        int[] data = null;
//
//        if (cursor.moveToFirst()) {
//            data = new int[cursor.getCount()];
//
//            int count = 0;
//
//            do {
//                data[count] = cursor.getInt(0);
//
//                count += 1;
//            } while (cursor.moveToNext());
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * V2 - Execute a custom sql query, returning the result as SearchData objects
     * Used for user search function
     *
     * TODO: When is this used and what queries are sent to this ?
     */
    public SearchData[] getRangeBySql(String sql) {

        open();

        try {
            Cursor cursor = db.rawQuery(sql, null);

            SearchData[] data = null;

            if (cursor.moveToFirst()) {
                data = new SearchData[cursor.getCount()];

                int count = 0;

                do {

                    SearchData sd = new SearchData();

                    sd.id = cursor.getInt(0);
                    sd.unique = cursor.getString(1);
                    sd.range = cursor.getString(2);
                    sd.plot = cursor.getString(3);

                    data[count] = sd;

                    count += 1;
                } while (cursor.moveToNext());
            }

            if (!cursor.isClosed()) {
                cursor.close();
            }

            return data;
        } catch (Exception n) {
            return null;
        }
    }

    /**
     * Returns range data based on the primary key for range Used when moving
     * between ranges on screen
     * //TODO add catch here for sqlite error
     */
    public RangeObject getRange(String first, String second, String unique, int id) {

        open();

        return ObservationUnitPropertyDao.Companion.getRangeFromId(
                first, second, unique,
                id);

//        RangeObject data = new RangeObject();
//        Cursor cursor;
//
//        data.plot = "";
//        data.plot_id = "";
//        data.range = "";
//
//        try {
//            cursor = db.query(RANGE, new String[]{TICK + ep.getString("ImportFirstName", "") + TICK,
//                            TICK + ep.getString("ImportSecondName", "") + TICK,
//                            TICK + ep.getString("ImportUniqueName", "") + TICK, "id"}, "id = ?",
//                    new String[]{String.valueOf(id)}, null, null, null
//            );
//
//            if (cursor.moveToFirst()) {
//                //data.entry = cursor.getString(0);
//                data.range = cursor.getString(0);
//                data.plot = cursor.getString(1);
//                data.plot_id = cursor.getString(2);
//
//            }
//
//            if (!cursor.isClosed()) {
//                cursor.close();
//            }
//        } catch (SQLiteException e) {
//            switchField(-1);
//            return null;
//        }
//
//        return data;
    }

    /**
     * Returns the range for items that match the specified id
     */
    public String getRangeFromId(String plot_id) {
        try {
            Cursor cursor = db.query(RANGE, new String[]{TICK + preferences.getString(GeneralKeys.PRIMARY_NAME, "") + TICK},
                    TICK + preferences.getString(GeneralKeys.UNIQUE_NAME, "") + TICK + " like ? ", new String[]{plot_id},
                    null, null, null);

            String myList = null;

            if (cursor.moveToFirst()) {
                myList = cursor.getString(0);
            }

            if (!cursor.isClosed()) {
                cursor.close();
            }

            return myList;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Helper function
     * v2.5
     */
    public void deleteTraitByValue(String studyId, String plotId, String traitDbId, String value) {

        open();

        ObservationDao.Companion.deleteTraitByValue(studyId, plotId, traitDbId, value);

//        try {
//            db.delete(USER_TRAITS, "rid like ? and parent like ? and userValue = ?",
//                    new String[]{rid, parent, value});
//        } catch (Exception e) {
//            Log.e(TAG, e.getMessage());
//        }
    }

    /**
     * Returns saved data based on trait, range and plot Meant for the on screen
     * drop downs
     */
    public String[] getDropDownRange(String trait, String plotId) {

        open();

        if (trait.length() == 0)
            return null;

        return ObservationUnitPropertyDao.Companion.getDropDownRange(preferences.getString(GeneralKeys.UNIQUE_NAME, ""), trait, plotId);

//        try {
//            Cursor cursor = db.query(RANGE, new String[]{TICK + trait + TICK},
//                    TICK + ep.getString("ImportUniqueName", "") + TICK + " like ? ", new String[]{plotId},
//                    null, null, null);
//
//            String[] myList = null;
//
//            if (cursor.moveToFirst()) {
//                myList = new String[cursor.getCount()];
//
//                int count = 0;
//
//                do {
//                    myList[count] = cursor.getString(0);
//
//                    count += 1;
//                } while (cursor.moveToNext());
//            }
//
//            if (!cursor.isClosed()) {
//                cursor.close();
//            }
//
//            return myList;
//        } catch (Exception e) {
//            return null;
//        }
    }

    /**
     * Returns the column names for the range table
     */
    public String[] getRangeColumnNames() {

        open();

//        if (db == null || !db.isOpen()) db = openHelper.getWritableDatabase();
        if (!isTableExists("ObservationUnitProperty")) {

            ArrayList<FieldObject> fields = StudyDao.Companion.getAllFieldObjects(
                    preferences.getString(GeneralKeys.FIELDS_LIST_SORT_ORDER, "date_import")
            );

            if (!fields.isEmpty()) {

                StudyDao.Companion.switchField(fields.get(0).getExp_id());

            }
        }

        return ObservationUnitPropertyDao.Companion.getRangeColumnNames();

//        Cursor cursor = db.rawQuery("SELECT * from " + RANGE + " limit 1", null);
//        //Cursor cursor = db.rawQuery("SELECT * from range limit 1", null);
//        String[] data = null;
//
//        if (cursor.moveToFirst()) {
//            int i = cursor.getColumnCount() - 1;
//
//            data = new String[i];
//
//            int k = 0;
//
//            for (int j = 0; j < cursor.getColumnCount(); j++) {
//
//                if (!cursor.getColumnName(j).equals("id")) {
//                    data[k] = cursor.getColumnName(j).replace("//", "/");
//                    k += 1;
//                }
//            }
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Returns the plot for items that match the specified id
     */
    public String getPlotFromId(String plot_id) {
        try {
            Cursor cursor = db.query(RANGE, new String[]{TICK + preferences.getString(GeneralKeys.SECONDARY_NAME, "") + TICK},
                    TICK + preferences.getString(GeneralKeys.UNIQUE_NAME, "") + TICK + " like ?", new String[]{plot_id},
                    null, null, null);

            String myList = null;

            if (cursor.moveToFirst()) {
                myList = cursor.getString(0);
            }

            if (!cursor.isClosed()) {
                cursor.close();
            }

            return myList;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Helper function
     * v1.6 - Amended to consider trait
     */
    public void deleteTrait(String studyId, String plotId, String traitDbId, String rep) {

        open();

        ObservationDao.Companion.deleteTrait(studyId, plotId, traitDbId, rep);

//        try {
//            db.delete(USER_TRAITS, "rid like ? and parent like ?",
//                    new String[]{rid, parent});
//        } catch (Exception e) {
//            Log.e(TAG, e.getMessage());
//        }
    }

    /**
     * Helper function
     * v2 - Delete trait
     */
    public void deleteTrait(String id) {

        open();

        ObservationVariableDao.Companion.deleteTrait(id);

//        try {
//            db.delete(TRAITS, "id = ?",
//                    new String[]{id});
//        } catch (Exception e) {
//            Log.e(TAG, e.getMessage());
//        }
    }

    /**
     * Helper function to delete all data in the table
     */
    public void deleteTable(String table) {

        try {
            db.delete(table, null, null);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * The above deleteTable function is only used to delete Traits table.
     */
    public void deleteTraitsTable() {

        open();

        try {
            ObservationVariableDao.Companion.deleteTraits();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }

//        deleteTable("TRAITS");
    }

    /**
     * Removes the range table
     */
    public void dropRange() {

        db.execSQL("DROP TABLE IF EXISTS ObservationUnitProperty");

//        db.execSQL("DROP TABLE IF EXISTS " + RANGE);
    }

    /**
     * Creates the range table based on column names
     */
    public void createRange(String[] data) {
        String sql = "CREATE TABLE " + RANGE + "(id INTEGER PRIMARY KEY,";

        for (int i = 0; i < data.length; i++) {
            if (i == data.length - 1) {
                sql += TICK + data[i] + TICK + " TEXT)";
            } else {
                sql += TICK + data[i] + TICK + " TEXT,";
            }
        }

        db.execSQL(sql);
    }

    /**
     * V2 - Returns titles of all trait columns as a comma delimited string
     */
    public String getTraitColumnsAsString() {
        try {
            String[] s = getAllTraits();

            String value = "";

            for (int i = 0; i < s.length; i++) {
                value += s[i];

                if (i < s.length - 1)
                    value += ",";
            }

            return value;
        } catch (Exception b) {
            return null;
        }

    }

    /**
     * Returns the column names for the range table
     */
    public String[] getRangeColumns() {

        open();

        return ObservationUnitPropertyDao.Companion.getRangeColumns();

//        Cursor cursor = db.rawQuery("SELECT * from range limit 1", null);
//
//        String[] data = null;
//
//        if (cursor.moveToFirst()) {
//            int i = cursor.getColumnCount() - 1;
//
//            data = new String[i];
//
//            int k = 0;
//
//            for (int j = 0; j < cursor.getColumnCount(); j++) {
//                if (!cursor.getColumnName(j).equals("id")) {
//                    data[k] = cursor.getColumnName(j);
//                    k += 1;
//                }
//            }
//        }
//
//        if (!cursor.isClosed()) {
//            cursor.close();
//        }
//
//        return data;
    }

    /**
     * Inserting traits data. The last field isVisible determines if the trait
     * is visible when using the app
     */
    public long insertTraits(TraitObject t) {
            /*String trait, String format, String defaultValue,
                             String minimum, String maximum, String details, String categories,
                             String isVisible, String realPosition) {*/
        open();

        return ObservationVariableDao.Companion.insertTraits(t);

//        if (hasTrait(t.getTrait())) {
//            return -1;
//        }
//
//        try {
//            this.insertTraits = this.bindValue(insertTraits, 1, t.getExternalDbId());
//            this.insertTraits = this.bindValue(insertTraits, 2, t.getTraitDataSource());
//            this.insertTraits = this.bindValue(insertTraits, 3, t.getTrait());
//            this.insertTraits = this.bindValue(insertTraits, 4, t.getFormat());
//            this.insertTraits = this.bindValue(insertTraits, 5, t.getDefaultValue());
//            this.insertTraits = this.bindValue(insertTraits, 6, t.getMinimum());
//            this.insertTraits = this.bindValue(insertTraits, 7, t.getMaximum());
//            this.insertTraits = this.bindValue(insertTraits, 8, t.getDetails());
//            this.insertTraits = this.bindValue(insertTraits, 9, t.getCategories());
//            this.insertTraits = this.bindValue(insertTraits, 10, String.valueOf(t.getVisible()));
//            this.insertTraits = this.bindValue(insertTraits, 11, t.getRealPosition());
//
//            return this.insertTraits.executeInsert();
//        } catch (Exception e) {
//            e.printStackTrace();
//            return -1;
//        }
    }

    public <T> SQLiteStatement bindValue(SQLiteStatement statement, Integer index, T value) {

        if (value != null) {
            statement.bindString(index, value.toString());
        } else {
            statement.bindNull(index);
        }
        return statement;
    }

    /**
     * V2 - Update the ordering of traits
     */
    public void updateTraitPosition(String id, int realPosition) {

        open();

        ObservationVariableDao.Companion.updateTraitPosition(id, realPosition);

//        try {
//            ContentValues c = new ContentValues();
//            c.put("realPosition", realPosition);
//
//            db.update(TRAITS, c, "id = ?", new String[]{id});
//
//        } catch (Exception e) {
//            Log.e(TAG, e.getMessage());
//        }
    }

    /**
     * V2 - Edit existing trait
     */
    public long editTraits(String traitDbId, String trait, String format, String defaultValue,
                           String minimum, String maximum, String details, String categories,
                           Boolean closeKeyboardOnOpen,
                           Boolean cropImage) {

        open();

        return ObservationVariableDao.Companion.editTraits(traitDbId, trait, format, defaultValue,
                minimum, maximum, details, categories, closeKeyboardOnOpen, cropImage);
//        try {
//            ContentValues c = new ContentValues();
//            c.put("trait", trait);
//            c.put("format", format);
//            c.put("defaultValue", defaultValue);
//            c.put("minimum", minimum);
//            c.put("maximum", maximum);
//            c.put("details", details);
//            c.put("categories", categories);
//
//            return db.update(TRAITS, c, "id = ?", new String[]{id});
//        } catch (Exception e) {
//            e.printStackTrace();
//            return -1;
//        }
    }

    public TraitObject getTraitByName(String name) {

        open();

        return ObservationVariableDao.Companion.getTraitByName(name);
    }

    public TraitObject getTraitByExternalDbId(String externalDbId, String traitDataSource) {

        open();

        return ObservationVariableDao.Companion.getTraitByExternalDbId(externalDbId, traitDataSource);
    }

    public long updateTrait(TraitObject trait) {

        open();

        return ObservationVariableDao.Companion.editTraits(trait.getId(), trait.getName(),
                trait.getFormat(), trait.getDefaultValue(), trait.getMinimum(), trait.getMaximum(),
                trait.getDetails(), trait.getCategories(), trait.getCloseKeyboardOnOpen(), trait.getCropImage());
    }

    public boolean checkUnique(HashMap<String, String> values) {

        open();

        return ObservationUnitDao.Companion.checkUnique(values);

//        Cursor cursor = db.rawQuery("SELECT unique_id from " + PLOTS, null);
//
//        if (cursor.moveToFirst()) {
//            do {
//                if (values.containsKey(cursor.getString(0))) {
//                    return false;
//                }
//            } while (cursor.moveToNext());
//        }
//
//        cursor.close();
//
//        return true;
    }

    public void updateImportDate(int studyId) {
        StudyDao.Companion.updateImportDate(studyId);
    }

    public void updateEditDate(int studyId) {
        StudyDao.Companion.updateEditDate(studyId);
    }

    public void updateExportDate(int studyId) {
        StudyDao.Companion.updateExportDate(studyId);
    }

    public void updateSyncDate(int studyId) {
        StudyDao.Companion.updateSyncDate(studyId);
    }

    public void updateStudyAlias(int studyId, String newName) {
        open();
        StudyDao.Companion.updateStudyAlias(studyId, newName);
        preferences.edit().putString(GeneralKeys.FIELD_ALIAS, newName).apply();
        close();
    }

    public void deleteField(int studyId) {

        open();

        if (studyId >= 0) {
            StudyDao.Companion.deleteField(studyId);
            resetSummaryLabels(studyId);
            deleteFieldSortOrder(studyId);
        }
    }

    private void resetSummaryLabels(int studyId) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().remove(GeneralKeys.SUMMARY_FILTER_ATTRIBUTES + "." + studyId)
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }

        deleteFieldSortOrder(studyId);
    }


    private void deleteFieldSortOrder(int studyId) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().remove(GeneralKeys.SORT_ORDER + "." + studyId)
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void switchField(int studyId) {

        open();

        //TODO lastplot is effectively erased when fields are switched, change this to persist and save each field's last plot.
        //potentially use preference map or db column

        preferences.edit().remove(GeneralKeys.LAST_PLOT).apply();
        //ep.edit().putString("lastplot", null).apply();

        //delete the old table
        db.execSQL("DROP TABLE IF EXISTS ObservationUnitProperty");

        StudyDao.Companion.switchField(studyId);

//        Cursor cursor;
//
//        // get array of plot attributes
//        if (exp_id == -1) {
//            cursor = db.rawQuery("SELECT plot_attributes.attribute_name FROM plot_attributes limit 1", null);
//            cursor.moveToFirst();
//        } else {
//            cursor = db.rawQuery("SELECT plot_attributes.attribute_name FROM plot_attributes WHERE plot_attributes.exp_id = " + exp_id, null);
//            cursor.moveToFirst();
//        }
//
//        String[] plotAttr = new String[cursor.getCount()];
//
//        for (int i = 0; i < cursor.getCount(); i++) {
//            plotAttr[i] = cursor.getString(0);
//            cursor.moveToNext();
//        }
//
//        cursor.close();
//
//        // create query to get data for range
//        String args = "";
//
//        for (String aPlotAttr : plotAttr) {
//            args = args + ", MAX(CASE WHEN plot_attributes.attribute_name = '" + aPlotAttr + "' THEN plot_values.attribute_value ELSE NULL END) AS \"" + aPlotAttr + "\"";
//        }
//
//        String query = "CREATE TABLE " + RANGE + " AS SELECT plots.plot_id as id" + args +
//                " FROM plots " +
//                "LEFT JOIN plot_values USING (plot_id) " +
//                "LEFT JOIN plot_attributes USING (attribute_id) " +
//                "WHERE plots.exp_id = '" + exp_id +
//                "' GROUP BY plots.plot_id";
//
//        // drop range table and import new query into range table
//        dropRange();
//        db.execSQL(query);
//
//        //String index = "CREATE INDEX range_unique_index ON " + RANGE + "(" + ep.getString("ImportUniqueName",null) + ")";
//        //db.execSQL(index);
    }

    public int checkFieldName(String name) {

        open();

        return StudyDao.Companion.checkFieldName(name);

//        Cursor c = db.rawQuery("SELECT exp_id FROM " + EXP_INDEX + " WHERE exp_name=?", new String[]{name});
//
//        if (c.moveToFirst()) {
//            return c.getInt(0);
//        }
//
//        return -1;
    }

    public int checkBrapiStudyUnique(String observationLevel, String brapiId) {

        open();

        return StudyDao.Companion.checkBrapiStudyUnique(observationLevel, brapiId);
    }

    public int createField(FieldObject e, List<String> columns, Boolean fromBrapi) {
        // String exp_name, String exp_alias, String unique_id, String primary_id, String secondary_id, String[] columns){

        open();

        return StudyDao.Companion.createField(e, timeStamp.format(Calendar.getInstance().getTime()), columns, fromBrapi);

//        long exp_id = checkFieldName(e.getExp_name());
//        if (exp_id != -1) {
//            return (int) exp_id;
//        }
//
//        // add to exp_index
//        ContentValues insertExp = new ContentValues();
//        insertExp.put("exp_name", e.getExp_name());
//        insertExp.put("exp_alias", e.getExp_alias());
//        insertExp.put("unique_id", e.getUnique_id());
//        insertExp.put("primary_id", e.getPrimary_id());
//        insertExp.put("secondary_id", e.getSecondary_id());
//        insertExp.put("exp_layout", e.getExp_layout());
//        insertExp.put("exp_species", e.getExp_species());
//        insertExp.put("exp_sort", e.getExp_sort());
//        insertExp.put("count", e.getCount());
//        insertExp.put("date_import", timeStamp.format(Calendar.getInstance().getTime()));
//        insertExp.put("exp_source", e.getExp_source());
//
//        exp_id = db.insert(EXP_INDEX, null, insertExp);
//
//        /* columns to plot_attributes
//        String[] columnNames = columns;
//        List<String> list = new ArrayList<>(Arrays.asList(columnNames));
//        list.remove("id");
//        columnNames = list.toArray(new String[0]);*/
//
//        for (String columnName : columns) {
//            ContentValues insertAttr = new ContentValues();
//            insertAttr.put("attribute_name", columnName);
//            insertAttr.put("exp_id", (int) exp_id);
//            db.insert(PLOT_ATTRIBUTES, null, insertAttr);
//        }
//
//        return (int) exp_id;
    }

    public void createFieldData(int studyId, List<String> columns, List<String> data) {

        open();

        StudyDao.Companion.createFieldData(studyId, columns, data);

//        // get unique_id, primary_id, secondary_id names from exp_id
//        Cursor cursor = db.rawQuery("SELECT exp_id.unique_id, exp_id.primary_id, exp_id.secondary_id from exp_id where exp_id.exp_id = " + exp_id, null);
//        cursor.moveToFirst();
//
//        // extract unique_id, primary_id, secondary_id indices
//        int[] plotIndices = new int[3];
//        //plotIndices[0] = Arrays.asList(columns).indexOf(cursor.getString(0));
//        //plotIndices[1] = Arrays.asList(columns).indexOf(cursor.getString(1));
//        //plotIndices[2] = Arrays.asList(columns).indexOf(cursor.getString(2));
//
//        plotIndices[0] = columns.indexOf(cursor.getString(0));
//        plotIndices[1] = columns.indexOf(cursor.getString(1));
//        plotIndices[2] = columns.indexOf(cursor.getString(2));
//
//        // add plot to plots table
//        ContentValues insertValues = new ContentValues();
//        insertValues.put("exp_id", exp_id);
//        insertValues.put("unique_id", data.get(plotIndices[0]));    //data[plotIndices[0]]);
//        insertValues.put("primary_id", data.get(plotIndices[1]));   //data[plotIndices[1]]);
//        insertValues.put("secondary_id", data.get(plotIndices[2])); //data[plotIndices[2]]);
//
//        long plot_id = db.insert(PLOTS, null, insertValues);
//
//        // add plot data plot_values table
//        for (int i = 0; i < columns.size(); i++) {
//            Cursor attribute_id = db.rawQuery("select plot_attributes.attribute_id from plot_attributes where plot_attributes.attribute_name = " + "'" + columns.get(i) + "'" + " and plot_attributes.exp_id = " + exp_id, null);
//            Integer attId = 0;
//
//            if (attribute_id.moveToFirst()) {
//                attId = attribute_id.getInt(0);
//            }
//
//            // We store these observationUnitDbId and observationUnitName in the plot table. Skip them here.
//            ContentValues plotValuesInsert = new ContentValues();
//            plotValuesInsert.put("attribute_id", attId);
//            plotValuesInsert.put("attribute_value", data.get(i));
//            plotValuesInsert.put("plot_id", (int) plot_id);
//            plotValuesInsert.put("exp_id", exp_id);
//            db.insert(PLOT_VALUES, null, plotValuesInsert);
//
//            attribute_id.close();
//
//        }
//
//        cursor.close();
    }

    /**
     * Delete all tables
     */

    public void deleteDatabase() {
        context.deleteDatabase(DATABASE_NAME);
    }

    /**
     * Import database
     */

    public void importDatabase(DocumentFile file) {
        String internalDbPath = getDatabasePath(this.context);

        close();

        String fileName = file.getName();

        if (fileName != null) {

            Log.w("File to copy", file.getName());

            File oldDb = new File(internalDbPath);

            //first check if the file to import is just a .db file
            if (fileName.endsWith(".db")) { //if it is import it old-style
                try {
                    BaseDocumentTreeUtil.Companion.copy(context, file, DocumentFile.fromFile(oldDb));

                    open();
                } catch (Exception e) {

                    Log.d("Database", e.toString());

                }
            } else if (fileName.endsWith(".zip")){ // for zip file, call the unzip function
                try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {

                    try (OutputStream output = new FileOutputStream(internalDbPath)) {
                        boolean isSampleDb = fileName.equals("sample_db.zip");
                        ZipUtil.Companion.unzip(context, input, output, isSampleDb);

                        open();
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new Exception();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (!isTableExists(Migrator.Study.tableName)) {

                Migrator.Companion.migrateSchema(db, getAllTraitObjects());

            }

        }
    }

    /**
     * Export database
     * TODO add documentation
     */
    public void exportDatabase(Context ctx, String filename) throws IOException {
        String internalDbPath = getDatabasePath(this.context);

        close();

        try {

            File oldDb = new File(internalDbPath);

            DocumentFile databaseDir = BaseDocumentTreeUtil.Companion.getDirectory(ctx, R.string.dir_database);

            if (databaseDir != null) {

                String dbFileName = "fieldbook.db";
                String prefFileName = filename + "_db_sharedpref";
                String zipFileName = filename + ".zip";

                DocumentFile dbDoc = databaseDir.findFile(dbFileName);
                DocumentFile prefDoc = databaseDir.findFile(prefFileName);
                if (dbDoc != null && dbDoc.exists()) {
                    dbDoc.delete();
                }

                if (prefDoc != null && prefDoc.exists()) {
                    prefDoc.delete();
                }

                DocumentFile backupDatabaseFile = databaseDir.createFile("*/*", dbFileName);
                DocumentFile backupPreferenceFile = databaseDir.createFile("*/*", prefFileName);


                DocumentFile zipFile = databaseDir.findFile(zipFileName);
                if (zipFile == null){
                    zipFile = databaseDir.createFile("*/*", zipFileName);
                }

                // copy the preferences in the backupPreferenceFile
                OutputStream tempStream = BaseDocumentTreeUtil.Companion.getFileOutputStream(context, R.string.dir_database, prefFileName);
                ObjectOutputStream objectStream = new ObjectOutputStream(tempStream);

                objectStream.writeObject(preferences.getAll());

                objectStream.close();

                if (tempStream != null) {
                    tempStream.close();
                }

                // add the .db file and preferences file to the zip file
                OutputStream outputStream = context.getContentResolver().openOutputStream(zipFile.getUri());
                if (backupDatabaseFile != null && backupPreferenceFile != null) {

                    BaseDocumentTreeUtil.Companion.copy(context, DocumentFile.fromFile(oldDb), backupDatabaseFile);

                    if (outputStream != null){
                        ZipUtil.Companion.zip(context, new DocumentFile[] { backupDatabaseFile, backupPreferenceFile }, outputStream);

                    }

                    // delete .db file and preferences file
                    if (backupDatabaseFile.exists()){
                        backupDatabaseFile.delete();
                    }

                    if (backupPreferenceFile.exists()){
                        backupPreferenceFile.delete();
                    }
                }
            }

        } catch (Exception e) {

            Log.e(TAG, e.getMessage());

        } finally {

            open();

        }
    }

    /**
     * Copy old file to new file
     */
    private void copyFile(File oldFile, File newFile) throws IOException {
        if (oldFile.exists()) {
            try {
                copyFileCall(new FileInputStream(oldFile), new FileOutputStream(newFile));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * V2 - Helper function to copy multiple files from asset to SDCard
     */
    public void copyFileOrDir(String fullPath, String path) {
        AssetManager assetManager = context.getAssets();
        String[] assets;

        try {
            assets = assetManager.list(path);

            if (assets.length == 0) {
                copyFile(fullPath, path);
            } else {
                File dir = new File(fullPath);

                if (!dir.exists())
                    dir.mkdir();

                for (String asset : assets) {
                    copyFileOrDir(fullPath, path + "/" + asset);
                }
            }
        } catch (IOException ex) {
            Log.e("Sample Data", "I/O Exception", ex);
        }
    }

    /**
     * V2 - Helper function to copy files from asset to SDCard
     */

    private void copyFile(String fullPath, String filename) {
        AssetManager assetManager = context.getAssets();

        InputStream in;
        OutputStream out;

        try {
            in = assetManager.open(filename);
            out = new FileOutputStream(fullPath + "/" + filename);

            byte[] buffer = new byte[1024];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            in.close();
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.e("Sample Data", e.getMessage());
        }

    }

    public static String getDatabasePath(Context context) {
        return context.getDatabasePath(DATABASE_NAME).getPath();
    }

    public boolean isTableExists(String tableName) {

        open();

        Cursor cursor = db.rawQuery("select DISTINCT tbl_name from sqlite_master where tbl_name = '" + tableName + "'", null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.close();
                return true;
            }
            cursor.close();
        }
        return false;
    }

    public boolean isTableEmpty(String tableName) {
        boolean empty = true;

        if (!isTableExists(tableName)) {
            return empty;
        }

        Cursor cur = db.rawQuery("SELECT COUNT(*) FROM " + tableName, null);
        if (cur != null) {
            if (cur != null && cur.moveToFirst()) {
                empty = (cur.getInt(0) == 0);
            }
            cur.close();
        }
        return empty;
    }

    //TODO replace with ObservationUnitPropertyDao call
    //copy of the above function, its only called once
    public boolean isRangeTableEmpty() {

        open();

        boolean empty = true;

        if (!isTableExists("ObservationUnitProperty")) {
            return empty;
        }

        Cursor cur = db.rawQuery("SELECT COUNT(*) FROM ObservationUnitProperty", null);
        if (cur != null) {
            if (cur != null && cur.moveToFirst()) {
                empty = (cur.getInt(0) == 0);
            }
            cur.close();
        }
        return empty;
    }

    public void updateStudySort(String sortString, int studyId) {

        open();

        StudyDao.Companion.updateStudySort(sortString, studyId);
    }

    public String[] getAllObservationUnitAttributeNames(int studyId) {

        open();

        return ObservationUnitAttributeDao.Companion.getAllNames(studyId);
    }

    public void beginTransaction() {
        openHelper.getWritableDatabase().beginTransaction();
    }

    public void endTransaction() {
        openHelper.getWritableDatabase().endTransaction();
    }

    public void setTransactionSuccessfull() {
        openHelper.getWritableDatabase().setTransactionSuccessful();
    }

    public String[] getAllTraitNames() {

        open();

        return ObservationVariableDao.Companion.getAllTraits();

    }

    public ObservationModel[] getAllObservations() {

        open();

        return ObservationDao.Companion.getAll();
    }

    public ObservationModel[] getAllObservationsOfVariable(String traitDbId) {

        open();

        return ObservationDao.Companion.getAllOfTrait(traitDbId);
    }

    public ObservationModel[] getAllObservations(SQLiteDatabase db) {

        return ObservationDao.Companion.getAll(db);
    }

    public ObservationModel[] getAllObservations(String studyId) {

        open();

        return ObservationDao.Companion.getAll(studyId);
    }

    public ObservationModel[] getAllObservations(String studyId, String unit) {

        open();

        return ObservationDao.Companion.getAll(studyId, unit);
    }

    public ObservationModel[] getAllObservations(String studyId, String plotId, String traitDbId) {

        open();

        return ObservationDao.Companion.getAll(studyId, plotId, traitDbId);
    }

//    public ObservationModel[] getAllObservationsFromAYear(String startDate, String endDate) {
//
//        open();
//
//        return ObservationDao.Companion.getAllFromAYear(startDate, endDate);
//    }

    public ObservationModel[] getAllObservationsFromAYear(String year) {

        open();

        return ObservationDao.Companion.getAllFromAYear(year);
    }

    public ObservationModel[] getRepeatedValues(String studyId, String plotId, String traitDbId) {

        open();

        return ObservationDao.Companion.getAllRepeatedValues(studyId, plotId, traitDbId);
    }

    public String getObservationUnitPropertyByPlotId(String uniqueName, String column, String uniqueId) {

        open();

        return ObservationUnitPropertyDao.Companion.getObservationUnitPropertyByUniqueId(uniqueName, column, uniqueId);
    }

    public void deleteObservation(String id) {

        open();

        ObservationDao.Companion.delete(id);
    }

    /**
     * When the version number changes, this class will recreate the entire
     * database
     * v1.6 - Amended to add new parent field. It is called parent in consideration to the enhanced search
     */
    private static class OpenHelper extends SQLiteOpenHelper {
        SharedPreferences preferences;
        DataHelper helper;
        OpenHelper(DataHelper helper) {
            super(helper.context, DATABASE_NAME, null, DATABASE_VERSION);
            preferences = PreferenceManager.getDefaultSharedPreferences(helper.context);
            this.helper = helper;
        }

        @Override
        public void onOpen(SQLiteDatabase db) {

            db.disableWriteAheadLogging();

            //enables foreign keys for cascade deletes
            db.rawQuery("PRAGMA foreign_keys=ON;", null).close();

        }

        @Override
        public void onCreate(SQLiteDatabase db) {

            //enables foreign keys for cascade deletes
            db.rawQuery("PRAGMA foreign_keys=ON;", null).close();

            db.execSQL("CREATE TABLE "
                    + RANGE
                    + "(id INTEGER PRIMARY KEY, range TEXT, plot TEXT, entry TEXT, plot_id TEXT, pedigree TEXT)");
            db.execSQL("CREATE TABLE "
                    + TRAITS
                    + "(id INTEGER PRIMARY KEY, external_db_id TEXT, trait_data_source TEXT, trait TEXT, format TEXT, defaultValue TEXT, minimum TEXT, maximum TEXT, details TEXT, categories TEXT, isVisible TEXT, realPosition int)");
            db.execSQL("CREATE TABLE "
                    + USER_TRAITS
                    + "(id INTEGER PRIMARY KEY, rid TEXT, parent TEXT, trait TEXT, userValue TEXT, timeTaken TEXT, person TEXT, location TEXT, rep TEXT, notes TEXT, exp_id TEXT, observation_db_id TEXT, last_synced_time TEXT)");
            db.execSQL("CREATE TABLE "
                    + PLOTS
                    + "(plot_id INTEGER PRIMARY KEY AUTOINCREMENT, exp_id INTEGER, unique_id VARCHAR, primary_id VARCHAR, secondary_id VARCHAR, coordinates VARCHAR)");
            db.execSQL("CREATE TABLE "
                    + PLOT_ATTRIBUTES
                    + "(attribute_id INTEGER PRIMARY KEY AUTOINCREMENT, attribute_name VARCHAR, exp_id INTEGER)");
            db.execSQL("CREATE TABLE "
                    + PLOT_VALUES
                    + "(attribute_value_id INTEGER PRIMARY KEY AUTOINCREMENT, attribute_id INTEGER, attribute_value VARCHAR, plot_id INTEGER, exp_id INTEGER)");
            db.execSQL("CREATE TABLE "
                    + EXP_INDEX
                    + "(exp_id INTEGER PRIMARY KEY AUTOINCREMENT, exp_name VARCHAR, exp_alias VARCHAR, unique_id VARCHAR, primary_id VARCHAR, secondary_id VARCHAR, exp_layout VARCHAR, exp_species VARCHAR, exp_sort VARCHAR, date_import VARCHAR, date_edit VARCHAR, date_export VARCHAR, count INTEGER, exp_source VARCHAR)");

            //Do not know why the unique constraint does not work
            //db.execSQL("CREATE UNIQUE INDEX expname ON " + EXP_INDEX +"(exp_name);");

            try {
                db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
                db.execSQL("INSERT INTO android_metadata(locale) VALUES('en_US')");
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }

            //migrate handles database upgrade from 8 -> 9
            Migrator.Companion.createTables(db, getAllTraitObjects(db));

            //this will force new databases to have full updates, otherwise sqliteopenhelper will not upgrade
            onUpgrade(db, 9, DATABASE_VERSION);
        }

        /**
         * Copy of getAllTraitObjects in DataHelper to migrate to version 9.
         */
        public ArrayList<TraitObject> getAllTraitObjects(SQLiteDatabase db) {

            ArrayList<TraitObject> list = new ArrayList<>();

            Cursor cursor = db.query(TRAITS, new String[]{"id", "trait", "format", "defaultValue",
                            "minimum", "maximum", "details", "categories", "isVisible", "realPosition"},
                    null, null, null, null, "realPosition"
            );

            if (cursor.moveToFirst()) {
                do {
                    TraitObject o = new TraitObject();

                    String traitName = cursor.getString(1);
                    String format = cursor.getString(2);

                    //v5.1.0 bugfix branch update, Android getString can return null.
                    if (traitName == null || format == null) continue;

                    o.setId(cursor.getString(0));
                    o.setName(traitName);
                    o.setFormat(format);
                    o.setDefaultValue(cursor.getString(3));
                    o.setMinimum(cursor.getString(4));
                    o.setMaximum(cursor.getString(5));
                    o.setDetails(cursor.getString(6));
                    o.setCategories(cursor.getString(7));
                    o.setRealPosition(cursor.getInt(9));

                    list.add(o);

                } while (cursor.moveToNext());
            }

            if (!cursor.isClosed()) {
                cursor.close();
            }

            return list;
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);

            if (oldVersion < 5) {
                db.execSQL("DROP TABLE IF EXISTS " + RANGE);
                db.execSQL("DROP TABLE IF EXISTS " + TRAITS);
                db.execSQL("DROP TABLE IF EXISTS " + USER_TRAITS);
            }

            if (oldVersion <= 5 & newVersion >= 5) {
                // add columns to tables
                db.execSQL("ALTER TABLE user_traits ADD COLUMN person TEXT");
                db.execSQL("ALTER TABLE user_traits ADD COLUMN location TEXT");
                db.execSQL("ALTER TABLE user_traits ADD COLUMN rep TEXT");
                db.execSQL("ALTER TABLE user_traits ADD COLUMN notes TEXT");
                db.execSQL("ALTER TABLE user_traits ADD COLUMN exp_id TEXT");
            }

            if (oldVersion <= 6 & newVersion >= 6) {
                // create new tables: plots, plotAttributes, plotAttributeValues, exp_index
                db.execSQL("CREATE TABLE "
                        + PLOTS
                        + "(plot_id INTEGER PRIMARY KEY AUTOINCREMENT, exp_id INTEGER, unique_id VARCHAR, primary_id VARCHAR, secondary_id VARCHAR, coordinates VARCHAR)");

                db.execSQL("CREATE TABLE "
                        + PLOT_ATTRIBUTES
                        + "(attribute_id INTEGER PRIMARY KEY AUTOINCREMENT, attribute_name VARCHAR, exp_id INTEGER)");
                db.execSQL("CREATE TABLE "
                        + PLOT_VALUES
                        + "(attribute_value_id INTEGER PRIMARY KEY AUTOINCREMENT, attribute_id INTEGER, attribute_value VARCHAR, plot_id INTEGER, exp_id INTEGER)");

                db.execSQL("CREATE TABLE "
                        + EXP_INDEX
                        + "(exp_id INTEGER PRIMARY KEY AUTOINCREMENT, exp_name VARCHAR, exp_alias VARCHAR, unique_id VARCHAR, primary_id VARCHAR, secondary_id VARCHAR, exp_layout VARCHAR, exp_species VARCHAR, exp_sort VARCHAR, date_import VARCHAR, date_edit VARCHAR, date_export VARCHAR, count INTEGER)");

                // add current range info to exp_index
                db.execSQL("insert into " + EXP_INDEX + "(exp_name, exp_alias, unique_id, primary_id, secondary_id) values (?,?,?,?,?)",
                        new String[]{preferences.getString(GeneralKeys.FIELD_FILE, ""), preferences.getString(GeneralKeys.FIELD_FILE, ""), preferences.getString(GeneralKeys.UNIQUE_NAME, ""), preferences.getString(GeneralKeys.PRIMARY_NAME, ""), preferences.getString(GeneralKeys.SECONDARY_NAME, "")});

                // convert current range table to plots
                Cursor cursor = db.rawQuery("SELECT * from range", null);

                // columns into attributes
                String[] columnNames = cursor.getColumnNames();
                List<String> list = new ArrayList<>(Arrays.asList(columnNames));
                list.remove("id");
                columnNames = list.toArray(new String[0]);

                for (String columnName1 : columnNames) {
                    ContentValues insertValues = new ContentValues();
                    insertValues.put("attribute_name", columnName1);
                    insertValues.put("exp_id", 1);
                    db.insert(PLOT_ATTRIBUTES, null, insertValues);
                }

                // plots into plots
                String cur2 = "SELECT " + TICK + preferences.getString(GeneralKeys.UNIQUE_NAME, "")
                        + TICK + ", " + TICK
                        + preferences.getString(GeneralKeys.PRIMARY_NAME, "")
                        + TICK + ", " + TICK
                        + preferences.getString(GeneralKeys.SECONDARY_NAME, "")
                        + TICK + " from range";

                Cursor cursor2 = db.rawQuery(cur2, null);

                if (cursor2.moveToFirst()) {
                    do {
                        ContentValues insertValues = new ContentValues();
                        insertValues.put("unique_id", cursor2.getString(0));
                        insertValues.put("primary_id", cursor2.getString(1));
                        insertValues.put("secondary_id", cursor2.getString(2));
                        insertValues.put("exp_id", 1);
                        db.insert(PLOTS, null, insertValues);
                    } while (cursor2.moveToNext());
                }

                // plot values into plot values
                for (String columnName : columnNames) {
                    String att_id = "select plot_attributes.attribute_id from plot_attributes where plot_attributes.attribute_name = " + "'" + columnName + "'" + " and plot_attributes.exp_id = ";
                    Cursor attribute_id = db.rawQuery(att_id + 1, null);
                    Integer attId = 0;

                    if (attribute_id.moveToFirst()) {
                        attId = attribute_id.getInt(0);
                    }

                    String att_val = "select range." + "'" + columnName + "'" + ", plots.plot_id from range inner join plots on range." + "'" + preferences.getString(GeneralKeys.UNIQUE_NAME, "") + "'" + "=plots.unique_id";
                    Cursor attribute_val = db.rawQuery(att_val, null);

                    if (attribute_val.moveToFirst()) {
                        do {
                            ContentValues insertValues = new ContentValues();
                            insertValues.put("attribute_id", attId);
                            insertValues.put("attribute_value", attribute_val.getString(0));
                            insertValues.put("plot_id", attribute_val.getInt(1));
                            insertValues.put("exp_id", 1);
                            db.insert(PLOT_VALUES, null, insertValues);
                        } while (attribute_val.moveToNext());
                    }
                }
            }

            if (oldVersion <= 7 & newVersion >= 8) {

                // add columns to tables for brapi integration

                db.execSQL("ALTER TABLE traits ADD COLUMN external_db_id VARCHAR");
                db.execSQL("ALTER TABLE traits ADD COLUMN trait_data_source VARCHAR");
                db.execSQL("ALTER TABLE exp_id ADD COLUMN exp_source VARCHAR");
                db.execSQL("ALTER TABLE user_traits ADD COLUMN observation_db_id TEXT");
                db.execSQL("ALTER TABLE user_traits ADD COLUMN last_synced_time TEXT");

            }

            if (oldVersion < 9 & newVersion >= 9) {

                // Backup database
                try {
                    helper.open();
                    helper.exportDatabase(helper.context, "backup_v8");
//                    File exportedDb = new File(ep2.getString(GeneralKeys.DEFAULT_STORAGE_LOCATION_DIRECTORY, Constants.MPATH) + Constants.BACKUPPATH + "/" + "backup_v8.db");
//                    File exportedSp = new File(ep2.getString(GeneralKeys.DEFAULT_STORAGE_LOCATION_DIRECTORY, Constants.MPATH) + Constants.BACKUPPATH + "/" + "backup_v8.db_sharedpref.xml");
//                    Utils.scanFile(helper.context, exportedDb);
//                    Utils.scanFile(helper.context, exportedSp);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e(TAG, e.getMessage());
                }

                Migrator.Companion.migrateSchema(db, getAllTraitObjects(db));

                preferences.edit().putInt(GeneralKeys.SELECTED_FIELD_ID, -1).apply();
            }

            if (oldVersion <= 9 && newVersion >= 10) {

                helper.fixGeoCoordinates(db);

                preferences.edit().putInt(GeneralKeys.SELECTED_FIELD_ID, -1).apply();

            }

            if (oldVersion <= 10 && newVersion >= 11) {

                // modify studies table for better handling of brapi study attributes
                db.execSQL("ALTER TABLE studies ADD COLUMN import_format TEXT");
                db.execSQL("ALTER TABLE studies ADD COLUMN date_sync TEXT");
                helper.populateImportFormat(db);
                helper.fixStudyAliases(db);

            }

            if (oldVersion <= 11 && newVersion >= 12) {
                // Add observation_unit_search_attribute column to studies table, use study_unique_id_name as default value
                db.execSQL("ALTER TABLE studies ADD COLUMN observation_unit_search_attribute TEXT");
                db.execSQL("UPDATE studies SET observation_unit_search_attribute = study_unique_id_name");
            }

//            if (oldVersion <= 12 && newVersion >= 13) {
//                // migrate to version that has new tables to handle spectral data and device parameters
//                Migrator.Companion.migrateToVersionExampleN(db);
//            }
        }
    }
}