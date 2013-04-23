/*
 *    Copyright 2013 Weald Technology Trading Limited
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.wealdtech.bitcoin.transaction;

import static com.wealdtech.Preconditions.checkNotNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.wealdtech.bitcoin.crypto.Crypto;
import com.wealdtech.bitcoin.crypto.ECKey;
import com.wealdtech.bitcoin.crypto.Sha256Hash;
import com.wealdtech.bitcoin.generator.Generator;
import com.wealdtech.bitcoin.generator.raw.TransactionGeneratorRawImpl;
import com.wealdtech.bitcoin.script.InputScript;
import com.wealdtech.bitcoin.script.Script;

/**
 * A transaction is the mechanism through which bitcoins move
 * from one address to another.
 *
 */
public class Transaction implements Serializable, Comparable<Transaction>
{
  private static final long serialVersionUID = -1239435645555914548L;

  private final int version;
  private final ImmutableList<TransactionInput> inputs;
  private final ImmutableList<TransactionOutput> outputs;
  private final int lockTime;

  /**
   * Create a transaction.  Note that this is a private constructor; all
   * transactions should be created using the supplied builder.
   */
  private Transaction(final int version, final List<TransactionInput> inputs, final List<TransactionOutput> outputs, final int lockTime)
  {
    this.version = version;
    if (inputs == null)
    {
      this.inputs = ImmutableList.of();
    }
    else
    {
      this.inputs = ImmutableList.copyOf(inputs);
    }
    if (outputs == null)
    {
      this.outputs = ImmutableList.of();
    }
    else
    {
      this.outputs = ImmutableList.copyOf(outputs);
    }
    this.lockTime = lockTime;
    validate();
  }

  private void validate()
  {
    checkNotNull(this.inputs, "Inputs cannot be null");
    checkNotNull(this.outputs, "Outputs cannot be null");
  }

  public int getVersion()
  {
    return this.version;
  }

  public ImmutableList<TransactionInput> getInputs()
  {
    return this.inputs;
  }

  public ImmutableList<TransactionOutput> getOutputs()
  {
    return this.outputs;
  }

  public long getLockTime()
  {
    return this.lockTime;
  }

  /**
   * Sign the inputs of a transaction
   * @param keys the keys used to sign the transaction inputs
   * @param scripts the scripts from the previous transactions (needed for signing)
   * @param hashType the type of hash mechanism
   * @return a new transaction, with the inputs signed
   */
  // FIXME implement
  public Transaction signInputs(final ImmutableMap<Sha256Hash, ECKey>keys, final ImmutableMap<Sha256Hash, Script>scripts, final HashType hashType)
  {
    // Obtain a skeleton of the current transaction, with all input scripts removed
    Transaction.Builder skeletonBuilder = new Transaction.Builder(this);
    List<TransactionInput> skeletonInputs = new ArrayList<>();
    for (TransactionInput input : this.getInputs())
    {
      skeletonInputs.add(new TransactionInput.Builder(input).script(null).build());
    }

    // A list of signed inputs
    List<TransactionInput> signedInputs = new ArrayList<>();

    skeletonBuilder = skeletonBuilder.inputs(skeletonInputs);

    // Create a signature for each of the inputs
    for (TransactionInput input : this.getInputs())
    {
      ECKey signingKey = keys.get(input.getTxHash());
      checkNotNull(signingKey, "Failed to obtain signing key for transaction input " + input.getTxHash() + ":" + input.getTxIndex());
      Script outputScript = scripts.get(input.getTxHash());
      checkNotNull(outputScript, "Failed to obtain output script for transaction input " + input.getTxHash() + ":" + input.getTxIndex());

      // Find the input that we want to sign
      List<TransactionInput> signingInputs = new ArrayList<>();
      TransactionInput unsignedInput = null;
      for (TransactionInput signingInput : skeletonInputs)
      {
        if (signingInput.getTxHash().equals(input.getTxHash()))
        {
          // This is the one we need to sign this time around, insert
          // the script from the previous transaction's output
          unsignedInput = signingInput;
          signingInputs.add(new TransactionInput.Builder(signingInput).script(outputScript).build());
        }
        else
        {
          // Pass this one through this time around
          signingInputs.add(signingInput);
        }
      }
      checkNotNull(unsignedInput, "Lost an input!");
      skeletonBuilder.inputs(signingInputs);

      // At this stage our transaction is stripped of all input scripts, except for
      // the one which we are attempting to sign which has the output script of the
      // transaction which gave us the funds we're trying to spend.  Get a raw version
      // of this transaction and sign it
      final Transaction skeleton = skeletonBuilder.build();
      Generator<Transaction> gen = new TransactionGeneratorRawImpl();
      gen.startGen();
      gen.generate(skeleton);
      ImmutableList<Byte> raw = gen.finishGen();
      ImmutableList<Byte> signature = Crypto.sign(raw, signingKey);

      // Add the signed input
      Script signatureScript = InputScript.create(signature, signingKey);
      signedInputs.add(new TransactionInput.Builder(unsignedInput).script(signatureScript).build());
    }

    // Build a new transaction, incorporating signatures as necessary
    Transaction signedTransaction = skeletonBuilder.inputs(signedInputs).build();
    // Return the new transaction
    return signedTransaction;
  }

  // Standard object methods follow
  @Override
  public String toString()
  {
    return Objects.toStringHelper(this)
                  .add("version", this.version)
                  .add("inputs", this.inputs)
                  .add("outputs", this.outputs)
                  .add("lockTime", this.lockTime)
                  .toString();
  }

  @Override
  public boolean equals(final Object that)
  {
    return (that instanceof Transaction) && (this.compareTo((Transaction)that) == 0);
  }

  @Override
  public int hashCode()
  {
    return Objects.hashCode(this.version, this.inputs, this.outputs, this.lockTime);
  }

  @Override
  public int compareTo(final Transaction that)
  {
    return ComparisonChain.start()
                          .compare(this.version, that.version)
                          .compare(this.inputs, that.inputs, Ordering.<TransactionInput>natural().lexicographical())
                          .compare(this.outputs, that.outputs, Ordering.<TransactionOutput>natural().lexicographical())
                          .compare(this.lockTime, that.lockTime)
                          .result();
  }

  /**
   * Builder for transactions.  This is the only way
   * in which a transaction should be created
   */
  public static class Builder
  {
    private int version;
    private List<TransactionInput> inputs;
    private List<TransactionOutput> outputs;
    private int lockTime;

    /**
     * Generate a new builder.
     */
    public Builder()
    {
    }

    /**
     * Generate a builder based on a prior object.
     * @param prior the prior object
     */
    public Builder(final Transaction prior)
    {
      this.version = prior.version;
      this.inputs = prior.inputs;
      this.outputs = prior.outputs;
      this.lockTime = prior.lockTime;
    }

    /**
     * Set the version
     * @param version the version
     * @return
     */
    public Builder version(final int version)
    {
      this.version = version;
      return this;
    }

    /**
     * Set the inputs
     * @param inputs the inputs
     * @return
     */
    public Builder inputs(final List<TransactionInput> inputs)
    {
      this.inputs = inputs;
      return this;
    }

    /**
     * Set the outputs
     * @param outputs the outputs
     * @return
     */
    public Builder outputs(final List<TransactionOutput> outputs)
    {
      this.outputs = outputs;
      return this;
    }

    /**
     * Set the lock time
     * @param lockTime the lock time
     * @return
     */
    public Builder lockTime(final int lockTime)
    {
      this.lockTime = lockTime;
      return this;
    }

    /**
     * Build the transaction
     * @return a new transaction
     */
    public Transaction build()
    {
      return new Transaction(this.version, this.inputs, this.outputs, this.lockTime);
    }
  }
}
