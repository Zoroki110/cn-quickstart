// Vetch the clearportx-fees DAR for all participants

import com.digitalasset.canton.console.ConsoleEnvironment

// Upload and vetch DAR for app-provider participant
val darPath = "/app/dars/clearportx-fees-1.0.0.dar"

// Load the DAR
splitwell.participants.participant1.dars.upload(darPath)

println("✅ Successfully uploaded and vetted clearportx-fees-1.0.0.dar")
