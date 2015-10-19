/*
The MIT License (MIT)

Copyright (c) 2015 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:
* 2015 creation

*/
package com.github.lindenb.jvarkit.tools.biostar;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.util.CloserUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.github.lindenb.jvarkit.util.Counter;
import com.github.lindenb.jvarkit.util.command.Command;
import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;

public class Biostar154220 extends AbstractBiostar154220
	{
	private static final org.apache.commons.logging.Log LOG = org.apache.commons.logging.LogFactory.getLog(Biostar154220.class);

		@Override
	public Command createCommand() {
		return new MyCommand();
		}
	
	static private class MyCommand extends AbstractBiostar154220.AbstractBiostar154220Command
		{
		@Override
		protected Collection<Throwable> call(String inputName) throws Exception
			{
			SamReader in =null;
			SAMRecordIterator iter=null;
			SAMFileWriter out=null;
			try
				{
				in= super.openSamReader(inputName);
				SAMFileHeader header= in.getFileHeader();
				if(header.getSortOrder()!=SAMFileHeader.SortOrder.unsorted)
					{
					return wrapException("input should be unsorted, reads sorted on REF/query-name e.g: see https://github.com/lindenb/jvarkit/wiki/SortSamRefName");
					}
				SAMSequenceDictionary dict=header.getSequenceDictionary();
				if(dict==null)
					{
					return wrapException("no dict !");
					}
				
				int prev_tid=-1;
				int depth_array[]=null;
			
				SAMFileHeader header2=header.clone();
				header2.addComment(getName()+" "+getVersion()+" "+getProgramCommandLine());
				out = super.openSAMFileWriter(header2, true);
				SAMSequenceDictionaryProgress progress=new SAMSequenceDictionaryProgress(dict);
				iter = in.iterator();
				List<SAMRecord> buffer=new ArrayList<>();
				for(;;)
					{
					SAMRecord rec =null;
					
					if(iter.hasNext())
						{
						rec = progress.watch(iter.next());
						}
					
					if(rec!=null && rec.getReadUnmappedFlag())
						{
						out.addAlignment(rec);
						continue;
						}
					//no more record or 
					if(!buffer.isEmpty() &&
						rec!=null &&
						buffer.get(0).getReadName().equals(rec.getReadName()) &&
						buffer.get(0).getReferenceIndex().equals(rec.getReferenceIndex())
						)
						{
						buffer.add(progress.watch(rec));
						}
					else if(buffer.isEmpty() && rec!=null)
						{
						buffer.add(progress.watch(rec));
						}
					else //dump buffer
						{
						if(!buffer.isEmpty())
							{
							final int tid = buffer.get(0).getReferenceIndex();
							if(prev_tid==-1 || prev_tid!=tid)
								{
								SAMSequenceRecord ssr=dict.getSequence(tid);
								prev_tid=tid;
								depth_array=null;
								System.gc();
								LOG.info("Alloc memory for contig "+ssr.getSequenceName()+" N="+ssr.getSequenceLength()+"*sizeof(int)");
								depth_array=new int[ssr.getSequenceLength()+1];//use a +1 pos
								Arrays.fill(depth_array, 0);
								}
							//position->coverage for this set of reads
							Counter<Integer> readposition2coverage=new Counter<Integer>();
							
							boolean dump_this_buffer=true;
							for(SAMRecord sr:buffer)
								{
								if(!dump_this_buffer) break;
								if(sr.isSecondaryOrSupplementary()) continue;
								if(sr.getDuplicateReadFlag()) continue;
								if(sr.getMappingQuality()==0) continue;
								
								
								Cigar cigar=sr.getCigar();
								if(cigar==null)
									{
									return wrapException("Cigar missing in "+rec.getSAMString());
									}
								int refPos1=sr.getAlignmentStart();
								for(CigarElement ce:cigar.getCigarElements())
									{
									final CigarOperator op =ce.getOperator();
									if(!op.consumesReferenceBases()) continue;
									if(op.consumesReadBases())
										{
										for(int x=0;x<ce.getLength() && refPos1+x< depth_array.length;++x)
											{
											int cov = (int)readposition2coverage.incr(refPos1+x);
											if( depth_array[refPos1+x]+cov > this.getCapDepth())
												{
												dump_this_buffer=false;
												break;
												}
											}
										}
									if(!dump_this_buffer) break;
									refPos1+=ce.getLength();
									}
								}
							if(dump_this_buffer)
								{
								//consumme this coverage
								for(Integer pos:readposition2coverage.keySet())
									{
									depth_array[pos]+= (int)readposition2coverage.count(pos);
									}
								for(SAMRecord sr:buffer)
									{
									out.addAlignment(sr);
									}
								}
							
							buffer.clear();
							}
						if(rec==null) break;
						buffer.add(rec);
						}
					}
				depth_array=null;
				progress.finish();
				return Collections.emptyList();
				}
			catch(Exception err)
				{
				return wrapException(err);
				}
			finally
				{
				CloserUtil.close(iter);
				CloserUtil.close(out);
				}
			}
	
		}
	
	public static void main(String[] args) throws IOException
		{
		new Biostar154220().instanceMainWithExit(args);
		}
		

	}