package com.NHGoogleOAuth;

/* Google OAuth Imports */
import java.io.IOException;

import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.os.AsyncTask;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.plus.Plus;

import org.json.JSONException;
import org.json.JSONObject;

import com.NHGoogleOAuth.R;
import com.worklight.androidgap.api.WLActionReceiver;

/* Worklight generated imports */
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;

import org.apache.cordova.CordovaActivity;

import com.worklight.androidgap.api.WL;
import com.worklight.androidgap.api.WLInitWebFrameworkResult;
import com.worklight.androidgap.api.WLInitWebFrameworkListener;

public class NHGoogleOAuth extends CordovaActivity implements WLInitWebFrameworkListener, 
		WLActionReceiver, ConnectionCallbacks, OnConnectionFailedListener {
	private static final String TAG = "com.NHGoogleOAuth";
	private static final String SCOPES = "https://www.googleapis.com/auth/plus.login";
	private static final int STATE_DEFAULT = 0;
	private static final int STATE_SIGN_IN = 1;
	private static final int STATE_IN_PROGRESS = 2;
	private static final int REQUEST_CODE_TOKEN_AUTH = 1;

	private static final int RC_SIGN_IN = 0;

	private static final int DIALOG_PLAY_SERVICES_ERROR = 0;

	// private static final String SAVED_PROGRESS = "sign_in_progress";

	// GoogleApiClient wraps our service connection to Google Play services and
	// provides access to the users sign in state and Google's APIs.
	private GoogleApiClient mGoogleApiClient;

	// We use mSignInProgress to track whether user has clicked sign in.
	// mSignInProgress can be one of three values:
	//
	// STATE_DEFAULT: The default state of the application before the user
	// has clicked 'sign in', or after they have clicked
	// 'sign out'. In this state we will not attempt to
	// resolve sign in errors and so will display our
	// Activity in a signed out state.
	// STATE_SIGN_IN: This state indicates that the user has clicked 'sign
	// in', so resolve successive errors preventing sign in
	// until the user has successfully authorized an account
	// for our app.
	// STATE_IN_PROGRESS: This state indicates that we have started an intent to
	// resolve an error, and so we should not start further
	// intents until the current intent completes.
	private int mSignInProgress;

	// Used to store the PendingIntent most recently returned by Google Play
	// services until the user clicks 'sign in'.
	private PendingIntent mSignInIntent;

	// Used to store the error code most recently returned by Google Play
	// services
	// until the user clicks 'sign in'.
	private int mSignInError;
	
	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);

		WL.createInstance(this);

		WL.getInstance().showSplashScreen(this);

		WL.getInstance().initializeWebFramework(getApplicationContext(), this);
		
		/*Google OAuth */
		WL.getInstance().addActionReceiver(this);
		mGoogleApiClient = buildGoogleApiClient();
	}

	/**
	 * The IBM Worklight web framework calls this method after its 
	 * initialization is complete and web resources are ready to be used.
	 */
 	public void onInitWebFrameworkComplete(WLInitWebFrameworkResult result){
		if (result.getStatusCode() == WLInitWebFrameworkResult.SUCCESS) {
			super.loadUrl(WL.getInstance().getMainHtmlFilePath());
		} else {
			handleWebFrameworkInitFailure(result);
		}
	}

	private void handleWebFrameworkInitFailure(WLInitWebFrameworkResult result){
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
		alertDialogBuilder.setNegativeButton(R.string.close, new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which){
				finish();
			}
		});

		alertDialogBuilder.setTitle(R.string.error);
		alertDialogBuilder.setMessage(result.getMessage());
		alertDialogBuilder.setCancelable(false).create().show();
	}
	
	/*The Following functions are all for Google OAuth */
	@Override
	public void onActionReceived(String arg0, JSONObject arg1) {
		if (arg0.equals("invokeSignIn")) {
			invokeSignIn();
		} else if (arg0.equals("initialize")) {
			initialConnect();
		} else if (arg0.equals("disconnect")) {
			disconnect();
		}
	}

	private void initialConnect() {
		mGoogleApiClient.connect();
	}

	private void invokeSignIn() {
		if (!mGoogleApiClient.isConnecting()) {
			// We only process button clicks when GoogleApiClient is not
			// transitioning
			// between connected and not connected.
			resolveSignInError();
		}
	}
	
	private GoogleApiClient buildGoogleApiClient() {
		// When we build the GoogleApiClient we specify where connected and
		// connection failed callbacks should be returned, which Google APIs our
		// app uses and which OAuth 2.0 scopes our app requests.
		return new GoogleApiClient.Builder(this).addConnectionCallbacks(this)
				.addOnConnectionFailedListener(this)
				.addApi(Plus.API, Plus.PlusOptions.builder().build())
				.addScope(Plus.SCOPE_PLUS_LOGIN).build();
	}

	@Override
	protected void onStop() {
		super.onStop();

		if (mGoogleApiClient.isConnected()) {
			mGoogleApiClient.disconnect();
		}
	}

	private void disconnect() {
		Plus.AccountApi.clearDefaultAccount(mGoogleApiClient);
		Plus.AccountApi.revokeAccessAndDisconnect(mGoogleApiClient);
		WL.getInstance().sendActionToJS("disconnected");
	}

	/*
	 * onConnected is called when our Activity successfully connects to Google
	 * Play services. onConnected indicates that an account was selected on the
	 * device, that the selected account has granted any requested permissions
	 * to our app and that we were able to establish a service connection to
	 * Google Play services.
	 */
	@Override
	public void onConnected(Bundle connectionHint) {
		// Reaching onConnected means we consider the user signed in.
		Log.i(TAG, "onConnected");

		AsyncTask<Void, Void, String> task = new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {
				String token = null;

				try {
					token = GoogleAuthUtil.getToken(NHGoogleOAuth.this,
							Plus.AccountApi.getAccountName(mGoogleApiClient),
							"oauth2:" + SCOPES);
				} catch (IOException transientEx) {
					// Network or server error, try later
					Log.e(TAG, transientEx.toString());
				} catch (UserRecoverableAuthException e) {
					// Recover (with e.getIntent())
					Log.e(TAG, e.toString());
					Intent recover = e.getIntent();
					startActivityForResult(recover, REQUEST_CODE_TOKEN_AUTH);
				} catch (GoogleAuthException authEx) {
					// The call is not ever expected to succeed
					// assuming you have already verified that
					// Google Play services is installed.
					Log.e(TAG, authEx.toString());
				}
				JSONObject accessToken = new JSONObject();
				try {
					accessToken.put("token", token);
				} catch (JSONException e) {
					Log.e(TAG, e.getMessage());
				}
				WL.getInstance().sendActionToJS("authorized", accessToken);
				return token;
			}

			@Override
			protected void onPostExecute(String token) {
				Log.i(TAG, token);
			}
		};
		task.execute();

		// Indicate that the sign in process is complete.
		mSignInProgress = STATE_DEFAULT;
	}

	/*
	 * onConnectionFailed is called when our Activity could not connect to
	 * Google Play services. onConnectionFailed indicates that the user needs to
	 * select an account, grant permissions or resolve an error in order to sign
	 * in.
	 */
	@Override
	public void onConnectionFailed(ConnectionResult result) {
		// Refer to the javadoc for ConnectionResult to see what error codes
		// might
		// be returned in onConnectionFailed.
		Log.i(TAG, "onConnectionFailed: ConnectionResult.getErrorCode() = "
				+ result.getErrorCode());

		WL.getInstance().sendActionToJS("notSignedIn");
		
		if (mSignInProgress != STATE_IN_PROGRESS) {
			// We do not have an intent in progress so we should store the
			// latest
			// error resolution intent for use when the sign in button is
			// clicked.
			mSignInIntent = result.getResolution();
			mSignInError = result.getErrorCode();

			if (mSignInProgress == STATE_SIGN_IN) {
				// STATE_SIGN_IN indicates the user already clicked the sign in
				// button
				// so we should continue processing errors until the user is
				// signed in
				// or they click cancel.
				resolveSignInError();
			}
		}
	}

	/*
	 * Starts an appropriate intent or dialog for user interaction to resolve
	 * the current error preventing the user from being signed in. This could be
	 * a dialog allowing the user to select an account, an activity allowing the
	 * user to consent to the permissions being requested by your app, a setting
	 * to enable device networking, etc.
	 */
	private void resolveSignInError() {
		if (mSignInIntent != null) {
			// We have an intent which will allow our user to sign in or
			// resolve an error. For example if the user needs to
			// select an account to sign in with, or if they need to consent
			// to the permissions your app is requesting.

			try {
				// Send the pending intent that we stored on the most recent
				// OnConnectionFailed callback. This will allow the user to
				// resolve the error currently preventing our connection to
				// Google Play services.
				mSignInProgress = STATE_IN_PROGRESS;
				startIntentSenderForResult(mSignInIntent.getIntentSender(),
						RC_SIGN_IN, null, 0, 0, 0);
			} catch (SendIntentException e) {
				Log.i(TAG,
						"Sign in intent could not be sent: "
								+ e.getLocalizedMessage());
				// The intent was canceled before it was sent. Attempt to
				// connect to
				// get an updated ConnectionResult.
				mSignInProgress = STATE_SIGN_IN;
				mGoogleApiClient.connect();
			}
		} else {
			showDialog(DIALOG_PLAY_SERVICES_ERROR);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case RC_SIGN_IN:
			if (resultCode == RESULT_OK) {
				// If the error resolution was successful we should continue
				// processing errors.
				mSignInProgress = STATE_SIGN_IN;
			} else {
				// If the error resolution was not successful or the user
				// canceled,
				// we should stop processing errors.
				mSignInProgress = STATE_DEFAULT;
			}

			if (!mGoogleApiClient.isConnecting()) {
				// If Google Play services resolved the issue with a dialog then
				// onStart is not called so we need to re-attempt connection
				// here.
				mGoogleApiClient.connect();
			}
			break;
		}
	}

	@Override
	public void onConnectionSuspended(int cause) {
		// The connection to Google Play services was lost for some reason.
		// We call connect() to attempt to re-establish the connection or get a
		// ConnectionResult that we can attempt to resolve.
		mGoogleApiClient.connect();
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case DIALOG_PLAY_SERVICES_ERROR:
			if (GooglePlayServicesUtil.isUserRecoverableError(mSignInError)) {
				return GooglePlayServicesUtil.getErrorDialog(mSignInError,
						this, RC_SIGN_IN,
						new DialogInterface.OnCancelListener() {
							@Override
							public void onCancel(DialogInterface dialog) {
								Log.e(TAG,
										"Google Play services resolution cancelled");
								mSignInProgress = STATE_DEFAULT;
							}
						});
			} else {
				return new AlertDialog.Builder(this)
						.setMessage("You don't have Google Play Services")
						.setPositiveButton(R.string.close,
								new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog,
											int which) {
										Log.e(TAG,
												"Google Play services error could not be "
														+ "resolved: "
														+ mSignInError);
										mSignInProgress = STATE_DEFAULT;
									}
								}).create();
			}
		default:
			return super.onCreateDialog(id);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Plus.AccountApi.clearDefaultAccount(mGoogleApiClient);
		mGoogleApiClient.disconnect();
		WL.getInstance().removeActionReceiver(this);
	}
}
