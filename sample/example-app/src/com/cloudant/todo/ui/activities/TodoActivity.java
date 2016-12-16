/*
 * Copyright © 2016 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.todo.ui.activities;

import android.app.FragmentManager;
import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import com.cloudant.sync.documentstore.ConflictException;
import com.cloudant.sync.replication.ReplicationService;
import com.cloudant.todo.R;
import com.cloudant.todo.Task;
import com.cloudant.todo.TaskAdapter;
import com.cloudant.todo.TasksModel;
import com.cloudant.todo.replicationpolicy.MyReplicationService;
import com.cloudant.todo.ui.dialogs.ProgressDialog;
import com.cloudant.todo.ui.dialogs.TaskDialog;

import java.net.URISyntaxException;
import java.util.List;

public class TodoActivity
    extends ListActivity
    implements OnSharedPreferenceChangeListener {

    private static final String LOG_TAG = "TodoActivity";

    private static final String FRAG_PROGRESS = "fragment_progress";
    private static final String FRAG_NEW_TASK = "fragment_new_task";

    // Main data model object
    private static TasksModel sTasks;
    ActionMode mActionMode = null;
    private TaskAdapter mTaskAdapter;

    // Add a handler to allow us to post UI updates on the main thread.
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Reference to our service.
    private ReplicationService mReplicationService;

    // Flag indicating whether the Activity is currently bound to the Service.
    private boolean mIsBound;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mReplicationService = ((ReplicationService.LocalBinder) service).getService();
            mReplicationService.addListener(new ReplicationService.SimpleReplicationCompleteListener() {
                @Override
                public void replicationComplete(int id) {
                    // Check if this is the pull replication
                    if (id == MyReplicationService.PULL_REPLICATION_ID) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                TodoActivity.this.replicationComplete();
                            }
                        });
                    }
                }

                @Override
                public void replicationErrored(int id) {
                    TodoActivity.this.replicationError();
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mReplicationService = null;
        }
    };

    private ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.context_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.action_delete:
                    deleteTaskAt(getListView().getCheckedItemPosition());
                    mode.finish();
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            getListView().setItemChecked(getListView().getCheckedItemPosition(), false);
            mActionMode = null;
        }
    };

    //
    // HELPER METHODS
    //

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(LOG_TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todo);

        // Load default settings when we're first created.
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Register to listen to the setting changes because replicators
        // uses information managed by shared preference.
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPref.registerOnSharedPreferenceChangeListener(this);

        // Protect creation of static variable.
        if (sTasks == null) {
            // Model needs to stay in existence for lifetime of app.
            sTasks = new TasksModel(this.getApplicationContext());
        }

        // Register this activity as the listener to replication updates
        // while its active.
        sTasks.setReplicationListener(this);

        // Load the tasks from the model
        this.reloadTasksFromModel();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MyReplicationService.class), mConnection, Context.BIND_AUTO_CREATE);
        mIsBound = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsBound) {
            unbindService(mConnection);
            mIsBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(LOG_TAG, "onDestroy()");
        super.onDestroy();

        // Clear our reference as listener.
        sTasks.setReplicationListener(null);
    }

    private void reloadReplicationSettings() {
        try {
            sTasks.reloadReplicationSettings(this);
        } catch (URISyntaxException e) {
            Log.e(LOG_TAG, "Unable to construct remote URI from configuration", e);
            Toast.makeText(getApplicationContext(),
                R.string.replication_error,
                Toast.LENGTH_LONG).show();
        }
    }

    private void reloadTasksFromModel() {
        List<Task> tasks = sTasks.allTasks();
        this.mTaskAdapter = new TaskAdapter(this, tasks);
        this.setListAdapter(this.mTaskAdapter);
    }

    private void createNewTask(String desc) {
        Task t = new Task(desc);
        sTasks.createDocument(t);
        reloadTasksFromModel();
    }

    private void toggleTaskCompleteAt(int position) {
        try {
            Task t = (Task) mTaskAdapter.getItem(position);
            t.setCompleted(!t.isCompleted());
            t = sTasks.updateDocument(t);
            mTaskAdapter.set(position, t);
        } catch (ConflictException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteTaskAt(int position) {
        try {
            Task t = (Task) mTaskAdapter.getItem(position);
            sTasks.deleteDocument(t);
            mTaskAdapter.remove(position);
            Toast.makeText(TodoActivity.this,
                "Deleted item : " + t.getDescription(),
                Toast.LENGTH_SHORT).show();
        } catch (ConflictException e) {
            throw new RuntimeException(e);
        }
    }

    public void onCompleteCheckboxClicked(View view) {
        this.toggleTaskCompleteAt(view.getId());
    }

//    void stopReplication() {
//        sTasks.stopAllReplications();
//        mTaskAdapter.notifyDataSetChanged();
//    }

    //
    // EVENT HANDLING
    //

    /**
     * Called by TasksModel when it receives a replication complete callback.
     * TasksModel takes care of calling this on the main thread.
     */
    public void replicationComplete() {
        reloadTasksFromModel();
        Toast.makeText(getApplicationContext(),
            R.string.replication_completed,
            Toast.LENGTH_LONG).show();
        showProgress(false);
    }

    /**
     * Called by TasksModel when it receives a replication error callback.
     * TasksModel takes care of calling this on the main thread.
     */
    public void replicationError() {
        Log.i(LOG_TAG, "error()");
        reloadTasksFromModel();
        Toast.makeText(getApplicationContext(),
            R.string.replication_error,
            Toast.LENGTH_LONG).show();
        showProgress(false);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        if (mActionMode != null) {
            mActionMode.finish();
        }

        // Make the newly clicked item the currently selected one.
        this.getListView().setItemChecked(position, true);
        mActionMode = this.startActionMode(mActionModeCallback);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.todo, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_new:
                showNewTaskDialog();
                return true;
            case R.id.action_download:
                showProgress(true);
                sTasks.startPullReplication();
                return true;
            case R.id.action_upload:
                showProgress(true);
                sTasks.startPushReplication();
                return true;
            case R.id.action_settings:
                this.startActivity(
                    new Intent().setClass(this, ReplicationSettingsActivity.class)
                );
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showNewTaskDialog() {
        FragmentManager fm = getFragmentManager();
        TaskDialog taskDialog = new TaskDialog();
        taskDialog.setListener(new TaskDialog.TaskCreatedListener() {
            @Override
            public void taskCreated(String description) {
                if (description.length() > 0) {
                    createNewTask(description);
                } else {
                    Toast.makeText(getApplicationContext(),
                        R.string.task_not_created,
                        Toast.LENGTH_LONG).show();
                }
            }
        });
        taskDialog.show(fm, FRAG_NEW_TASK);
    }

    private void showProgressDialog() {
        FragmentManager fm = getFragmentManager();
        ProgressDialog progressDialog = new ProgressDialog();
        progressDialog.setListener(new ProgressDialog.ProgressCancelListener() {
            @Override
            public void cancel() {
                sTasks.stopAllReplications();
                mTaskAdapter.notifyDataSetChanged();
            }
        });
        progressDialog.show(fm, FRAG_PROGRESS);

    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        Log.d(LOG_TAG, "onSharedPreferenceChanged()");
        reloadReplicationSettings();
    }

    private void showProgress(boolean visible) {
        if (visible) {
            showProgressDialog();
        } else {
            FragmentManager fm = getFragmentManager();
            ProgressDialog dialog = ((ProgressDialog) fm.findFragmentByTag(FRAG_PROGRESS));
            if (dialog != null) {
                dialog.dismiss();
            }
        }
    }

}
