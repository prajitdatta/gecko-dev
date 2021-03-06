/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.fxa.activities;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.mozilla.gecko.R;
import org.mozilla.gecko.background.common.log.Logger;
import org.mozilla.gecko.background.fxa.FxAccountClient10.RequestDelegate;
import org.mozilla.gecko.background.fxa.FxAccountClient20.LoginResponse;
import org.mozilla.gecko.background.fxa.FxAccountClientException.FxAccountClientRemoteException;
import org.mozilla.gecko.background.fxa.FxAccountUtils;
import org.mozilla.gecko.background.fxa.PasswordStretcher;
import org.mozilla.gecko.background.fxa.QuickPasswordStretcher;
import org.mozilla.gecko.fxa.FxAccountConstants;
import org.mozilla.gecko.fxa.authenticator.AndroidFxAccount;
import org.mozilla.gecko.fxa.login.Engaged;
import org.mozilla.gecko.fxa.login.State;
import org.mozilla.gecko.fxa.tasks.FxAccountSetupTask.ProgressDisplay;
import org.mozilla.gecko.sync.ExtendedJSONObject;
import org.mozilla.gecko.sync.SyncConfiguration;
import org.mozilla.gecko.sync.setup.Constants;
import org.mozilla.gecko.sync.setup.activities.ActivityUtils;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

abstract public class FxAccountAbstractSetupActivity extends FxAccountAbstractActivity implements ProgressDisplay {
  public static final String EXTRA_EMAIL = "email";
  public static final String EXTRA_PASSWORD = "password";
  public static final String EXTRA_PASSWORD_SHOWN = "password_shown";
  public static final String EXTRA_YEAR = "year";
  public static final String EXTRA_EXTRAS = "extras";

  public static final String JSON_KEY_AUTH = "auth";
  public static final String JSON_KEY_SERVICES = "services";
  public static final String JSON_KEY_SYNC = "sync";

  public FxAccountAbstractSetupActivity() {
    super(CANNOT_RESUME_WHEN_ACCOUNTS_EXIST | CANNOT_RESUME_WHEN_LOCKED_OUT);
  }

  protected FxAccountAbstractSetupActivity(int resume) {
    super(resume);
  }

  private static final String LOG_TAG = FxAccountAbstractSetupActivity.class.getSimpleName();

  // By default, any custom server configuration is only shown when the account
  // is configured to use a custom server.
  private static final boolean ALWAYS_SHOW_CUSTOM_SERVER_LAYOUT = false;

  protected int minimumPasswordLength = 8;

  protected AutoCompleteTextView emailEdit;
  protected EditText passwordEdit;
  protected Button showPasswordButton;
  protected TextView remoteErrorTextView;
  protected Button button;
  protected ProgressBar progressBar;

  private String authServerEndpoint;
  private String syncServerEndpoint;

  protected String getAuthServerEndpoint() {
    return authServerEndpoint;
  }

  protected String getTokenServerEndpoint() {
    return syncServerEndpoint;
  }

  protected void createShowPasswordButton() {
    showPasswordButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        boolean isShown = passwordEdit.getTransformationMethod() instanceof SingleLineTransformationMethod;
        setPasswordButtonShown(!isShown);
      }
    });
  }

  @SuppressWarnings("deprecation")
  protected void setPasswordButtonShown(boolean shouldShow) {
    // Changing input type loses position in edit text; let's try to maintain it.
    int start = passwordEdit.getSelectionStart();
    int stop = passwordEdit.getSelectionEnd();

    if (!shouldShow) {
      passwordEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
      showPasswordButton.setText(R.string.fxaccount_password_show);
      showPasswordButton.setBackgroundDrawable(getResources().getDrawable(R.drawable.fxaccount_password_button_show_background));
      showPasswordButton.setTextColor(getResources().getColor(R.color.fxaccount_password_show_textcolor));
    } else {
      passwordEdit.setTransformationMethod(SingleLineTransformationMethod.getInstance());
      showPasswordButton.setText(R.string.fxaccount_password_hide);
      showPasswordButton.setBackgroundDrawable(getResources().getDrawable(R.drawable.fxaccount_password_button_hide_background));
      showPasswordButton.setTextColor(getResources().getColor(R.color.fxaccount_password_hide_textcolor));
    }
    passwordEdit.setSelection(start, stop);
  }

  protected void linkifyPolicy() {
    TextView policyView = (TextView) ensureFindViewById(null, R.id.policy, "policy links");
    final String linkTerms = getString(R.string.fxaccount_link_tos);
    final String linkPrivacy = getString(R.string.fxaccount_link_pn);
    final String linkedTOS = "<a href=\"" + linkTerms + "\">" + getString(R.string.fxaccount_policy_linktos) + "</a>";
    final String linkedPN = "<a href=\"" + linkPrivacy + "\">" + getString(R.string.fxaccount_policy_linkprivacy) + "</a>";
    policyView.setText(getString(R.string.fxaccount_create_account_policy_text, linkedTOS, linkedPN));
    final boolean underlineLinks = true;
    ActivityUtils.linkifyTextView(policyView, underlineLinks);
  }

  protected void hideRemoteError() {
    remoteErrorTextView.setVisibility(View.INVISIBLE);
  }

  protected void showRemoteError(Exception e, int defaultResourceId) {
    if (e instanceof IOException) {
      remoteErrorTextView.setText(R.string.fxaccount_remote_error_COULD_NOT_CONNECT);
    } else if (e instanceof FxAccountClientRemoteException) {
      showClientRemoteException((FxAccountClientRemoteException) e);
    } else {
      remoteErrorTextView.setText(defaultResourceId);
    }
    Logger.warn(LOG_TAG, "Got exception; showing error message: " + remoteErrorTextView.getText().toString(), e);
    remoteErrorTextView.setVisibility(View.VISIBLE);
  }

  protected void showClientRemoteException(final FxAccountClientRemoteException e) {
    remoteErrorTextView.setText(e.getErrorMessageStringResource());
  }

  protected void addListeners() {
    TextChangedListener textChangedListener = new TextChangedListener();
    EditorActionListener editorActionListener = new EditorActionListener();
    FocusChangeListener focusChangeListener = new FocusChangeListener();

    emailEdit.addTextChangedListener(textChangedListener);
    emailEdit.setOnEditorActionListener(editorActionListener);
    emailEdit.setOnFocusChangeListener(focusChangeListener);
    passwordEdit.addTextChangedListener(textChangedListener);
    passwordEdit.setOnEditorActionListener(editorActionListener);
    passwordEdit.setOnFocusChangeListener(focusChangeListener);
  }

  protected class FocusChangeListener implements OnFocusChangeListener {
    @Override
    public void onFocusChange(View v, boolean hasFocus) {
      if (hasFocus) {
        return;
      }
      updateButtonState();
    }
  }

  protected class EditorActionListener implements OnEditorActionListener {
    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
      updateButtonState();
      return false;
    }
  }

  protected class TextChangedListener implements TextWatcher {
    @Override
    public void afterTextChanged(Editable s) {
      updateButtonState();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      // Do nothing.
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
      // Do nothing.
    }
  }

  protected boolean shouldButtonBeEnabled() {
    final String email = emailEdit.getText().toString();
    final String password = passwordEdit.getText().toString();

    boolean enabled =
        (email.length() > 0) &&
        Patterns.EMAIL_ADDRESS.matcher(email).matches() &&
        (password.length() >= minimumPasswordLength);
    return enabled;
  }

  protected boolean updateButtonState() {
    boolean enabled = shouldButtonBeEnabled();
    if (!enabled) {
      // The user needs to do something before you can interact with the button;
      // presumably that interaction will fix whatever error is shown.
      hideRemoteError();
    }
    if (enabled != button.isEnabled()) {
      Logger.debug(LOG_TAG, (enabled ? "En" : "Dis") + "abling button.");
      button.setEnabled(enabled);
    }
    return enabled;
  }

  @Override
  public void showProgress() {
    progressBar.setVisibility(View.VISIBLE);
    button.setVisibility(View.INVISIBLE);
  }

  @Override
  public void dismissProgress() {
    progressBar.setVisibility(View.INVISIBLE);
    button.setVisibility(View.VISIBLE);
  }

  public Intent makeSuccessIntent(String email, LoginResponse result) {
    Intent successIntent;
    if (result.verified) {
      successIntent = new Intent(this, FxAccountVerifiedAccountActivity.class);
    } else {
      successIntent = new Intent(this, FxAccountConfirmAccountActivity.class);
    }
    // Per http://stackoverflow.com/a/8992365, this triggers a known bug with
    // the soft keyboard not being shown for the started activity. Why, Android, why?
    successIntent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    return successIntent;
  }

  protected abstract class AddAccountDelegate implements RequestDelegate<LoginResponse> {
    public final String email;
    public final PasswordStretcher passwordStretcher;
    public final String serverURI;
    public final Map<String, Boolean> selectedEngines;

    public AddAccountDelegate(String email, PasswordStretcher passwordStretcher, String serverURI) {
      this(email, passwordStretcher, serverURI, null);
    }

    public AddAccountDelegate(String email, PasswordStretcher passwordStretcher, String serverURI, Map<String, Boolean> selectedEngines) {
      if (email == null) {
        throw new IllegalArgumentException("email must not be null");
      }
      if (passwordStretcher == null) {
        throw new IllegalArgumentException("passwordStretcher must not be null");
      }
      if (serverURI == null) {
        throw new IllegalArgumentException("serverURI must not be null");
      }
      this.email = email;
      this.passwordStretcher = passwordStretcher;
      this.serverURI = serverURI;
      // selectedEngines can be null, which means don't write
      // userSelectedEngines to prefs. This makes any created meta/global record
      // have the default set of engines to sync.
      this.selectedEngines = selectedEngines;
    }

    @Override
    public void handleSuccess(LoginResponse result) {
      Logger.info(LOG_TAG, "Got success response; adding Android account.");

      // We're on the UI thread, but it's okay to create the account here.
      AndroidFxAccount fxAccount;
      try {
        final String profile = Constants.DEFAULT_PROFILE;
        final String tokenServerURI = getTokenServerEndpoint();
        // It is crucial that we use the email address provided by the server
        // (rather than whatever the user entered), because the user's keys are
        // wrapped and salted with the initial email they provided to
        // /create/account. Of course, we want to pass through what the user
        // entered locally as much as possible, so we create the Android account
        // with their entered email address, etc.
        // The passwordStretcher should have seen this email address before, so
        // we shouldn't be calculating the expensive stretch twice.
        byte[] quickStretchedPW = passwordStretcher.getQuickStretchedPW(result.remoteEmail.getBytes("UTF-8"));
        byte[] unwrapkB = FxAccountUtils.generateUnwrapBKey(quickStretchedPW);
        State state = new Engaged(email, result.uid, result.verified, unwrapkB, result.sessionToken, result.keyFetchToken);
        fxAccount = AndroidFxAccount.addAndroidAccount(getApplicationContext(),
            email,
            profile,
            serverURI,
            tokenServerURI,
            state);
        if (fxAccount == null) {
          throw new RuntimeException("Could not add Android account.");
        }

        if (selectedEngines != null) {
          Logger.info(LOG_TAG, "User has selected engines; storing to prefs.");
          SyncConfiguration.storeSelectedEnginesToPrefs(fxAccount.getSyncPrefs(), selectedEngines);
        }
      } catch (Exception e) {
        handleError(e);
        return;
      }

      // For great debugging.
      if (FxAccountUtils.LOG_PERSONAL_INFORMATION) {
        fxAccount.dump();
      }

      // The GetStarted activity has called us and needs to return a result to the authenticator.
      final Intent intent = new Intent();
      intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, email);
      intent.putExtra(AccountManager.KEY_ACCOUNT_TYPE, FxAccountConstants.ACCOUNT_TYPE);
      // intent.putExtra(AccountManager.KEY_AUTHTOKEN, accountType);
      setResult(RESULT_OK, intent);

      // Show success activity depending on verification status.
      Intent successIntent = makeSuccessIntent(email, result);
      startActivity(successIntent);
      finish();
    }
  }

  /**
   * Factory function that produces a new PasswordStretcher instance.
   *
   * @return PasswordStretcher instance.
   */
  protected PasswordStretcher makePasswordStretcher(String password) {
    return new QuickPasswordStretcher(password);
  }

  protected abstract static class GetAccountsAsyncTask extends AsyncTask<Void, Void, Account[]> {
    protected final Context context;

    public GetAccountsAsyncTask(Context context) {
      super();
      this.context = context;
    }

    @Override
    protected Account[] doInBackground(Void... params) {
      return AccountManager.get(context).getAccounts();
    }
  }

  /**
   * This updates UI, so needs to be done on the foreground thread.
   */
  protected void populateEmailAddressAutocomplete(Account[] accounts) {
    // First a set, since we don't want repeats.
    final Set<String> emails = new HashSet<String>();
    for (Account account : accounts) {
      if (!Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
        continue;
      }
      emails.add(account.name);
    }

    // And then sorted in alphabetical order.
    final String[] sortedEmails = emails.toArray(new String[emails.size()]);
    Arrays.sort(sortedEmails);

    final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, sortedEmails);
    emailEdit.setAdapter(adapter);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  protected void updateFromIntentExtras() {
    // Only set email/password in onCreate; we don't want to overwrite edited values onResume.
    if (getIntent() != null && getIntent().getExtras() != null) {
      Bundle bundle = getIntent().getExtras();
      emailEdit.setText(bundle.getString(EXTRA_EMAIL));
      passwordEdit.setText(bundle.getString(EXTRA_PASSWORD));
      setPasswordButtonShown(bundle.getBoolean(EXTRA_PASSWORD_SHOWN, false));
    }

    // This sets defaults as well as extracting from extras, so it's not conditional.
    updateServersFromIntentExtras(getIntent());

    if (FxAccountUtils.LOG_PERSONAL_INFORMATION) {
      FxAccountUtils.pii(LOG_TAG, "Using auth server: " + authServerEndpoint);
      FxAccountUtils.pii(LOG_TAG, "Using sync server: " + syncServerEndpoint);
    }

    updateCustomServerView();
  }

  @Override
  public void onResume() {
    super.onResume();

    // Getting Accounts accesses databases on disk, so needs to be done on a
    // background thread.
    final GetAccountsAsyncTask task = new GetAccountsAsyncTask(this) {
      @Override
      public void onPostExecute(Account[] accounts) {
        populateEmailAddressAutocomplete(accounts);
      }
    };
    task.execute();
  }

  protected Bundle makeExtrasBundle(String email, String password) {
    final Bundle bundle = new Bundle();

    // Pass through any extras that we were started with.
    if (getIntent() != null && getIntent().getExtras() != null) {
      bundle.putAll(getIntent().getExtras());
    }

    // Overwrite with current settings.
    if (email == null) {
      email = emailEdit.getText().toString();
    }
    if (password == null) {
      password = passwordEdit.getText().toString();
    }
    bundle.putString(EXTRA_EMAIL, email);
    bundle.putString(EXTRA_PASSWORD, password);

    boolean isPasswordShown = passwordEdit.getTransformationMethod() instanceof SingleLineTransformationMethod;
    bundle.putBoolean(EXTRA_PASSWORD_SHOWN, isPasswordShown);

    return bundle;
  }

  protected void startActivityInstead(Class<?> cls, int requestCode, Bundle extras) {
    Intent intent = new Intent(this, cls);
    if (extras != null) {
      intent.putExtras(extras);
    }
    // Per http://stackoverflow.com/a/8992365, this triggers a known bug with
    // the soft keyboard not being shown for the started activity. Why, Android, why?
    intent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    startActivityForResult(intent, requestCode);
  }

  protected void updateServersFromIntentExtras(Intent intent) {
    // Start with defaults.
    this.authServerEndpoint = FxAccountConstants.DEFAULT_AUTH_SERVER_ENDPOINT;
    this.syncServerEndpoint = FxAccountConstants.DEFAULT_TOKEN_SERVER_ENDPOINT;

    if (intent == null) {
      Logger.warn(LOG_TAG, "Intent is null; ignoring and using default servers.");
      return;
    }

    final String extrasString = intent.getStringExtra(EXTRA_EXTRAS);

    if (extrasString == null) {
      return;
    }

    final ExtendedJSONObject extras;
    final ExtendedJSONObject services;
    try {
      extras = new ExtendedJSONObject(extrasString);
      services = extras.getObject(JSON_KEY_SERVICES);
    } catch (Exception e) {
      Logger.warn(LOG_TAG, "Got exception parsing extras; ignoring and using default servers.");
      return;
    }

    String authServer = extras.getString(JSON_KEY_AUTH);
    String syncServer = services == null ? null : services.getString(JSON_KEY_SYNC);

    if (authServer != null) {
      this.authServerEndpoint = authServer;
    }
    if (syncServer != null) {
      this.syncServerEndpoint = syncServer;
    }

    if (FxAccountConstants.DEFAULT_TOKEN_SERVER_ENDPOINT.equals(syncServerEndpoint) &&
        !FxAccountConstants.DEFAULT_AUTH_SERVER_ENDPOINT.equals(authServerEndpoint)) {
      // We really don't want to hard-code assumptions about server
      // configurations into client code in such a way that if and when the
      // situation is relaxed, the client code stops valid usage. Instead, we
      // warn. This configuration should present itself as an auth exception at
      // Sync time.
      Logger.warn(LOG_TAG, "Mozilla's Sync token servers only works with Mozilla's auth servers. Sync will likely be mis-configured.");
    }
  }

  protected void updateCustomServerView() {
    final boolean shouldShow =
        ALWAYS_SHOW_CUSTOM_SERVER_LAYOUT ||
        !FxAccountConstants.DEFAULT_AUTH_SERVER_ENDPOINT.equals(authServerEndpoint) ||
        !FxAccountConstants.DEFAULT_TOKEN_SERVER_ENDPOINT.equals(syncServerEndpoint);

    if (!shouldShow) {
      setCustomServerViewVisibility(View.GONE);
      return;
    }

    final TextView authServerView = (TextView) ensureFindViewById(null, R.id.account_server_summary, "account server");
    final TextView syncServerView = (TextView) ensureFindViewById(null, R.id.sync_server_summary, "Sync server");
    authServerView.setText(authServerEndpoint);
    syncServerView.setText(syncServerEndpoint);

    setCustomServerViewVisibility(View.VISIBLE);
  }

  protected void setCustomServerViewVisibility(int visibility) {
    ensureFindViewById(null, R.id.account_server_layout, "account server layout").setVisibility(visibility);
    ensureFindViewById(null, R.id.sync_server_layout, "sync server layout").setVisibility(visibility);
  }
}
